package com.cyssoftware.effyflow.cotizacion

import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.SyncAuthenticationException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant

/** Cotizacion-specific durable outbox. A retry always sends the JSON stored on first enqueue. */
class CotizacionOutboxSync(
    private val repository: CotizacionRepository,
    private val now: () -> Instant = Instant::now
) {
    private val db: EffyDatabase get() = repository.db

    data class Resultado(val estado: String, val mensaje: String)

    fun congelar(uuid: String, usuarioActivoId: Long): String {
        val before = repository.buscar(uuid)
        require(before.usuarioAutorId == usuarioActivoId) { "La Cotizacion pertenece a otro usuario." }
        val existing = db.cotizacionesQueries.getCotizacionOutbox(uuid).executeAsOneOrNull()
        if (existing != null) return existing.payload_json
        require(before.estadoLocal == "BORRADOR_LOCAL") { "Solo se puede marcar para envio un borrador local." }
        val prepared = repository.prepararCaptura(uuid)
        val payload = buildJsonObject {
            put("uuid", prepared.uuid)
            put("fechaComercial", prepared.fechaComercial)
            put("revisionComercialId", prepared.revisionComercialId)
            put("revisionHuellaSha256", prepared.revisionHuellaSha256)
            put("dispositivoId", prepared.dispositivoId)
            prepared.agenteId?.let { put("agenteId", it) }
            put("clienteId", prepared.clienteId)
            put("documentoSolicitado", prepared.documentoSolicitado)
            prepared.observaciones?.let { put("observaciones", it) }
            put("renglones", JsonArray(prepared.renglones.map { line -> buildJsonObject {
                put("productoId", line.productoId)
                line.presentacionId?.let { put("presentacionId", it) }
                put("cantidad", decimal(line.cantidad))
                put("precioReferencia", decimal(line.precioReferencia))
                put("descuentoSolicitado", decimal(line.descuentoSolicitado))
            } }))
        }.toString()

        db.transaction {
            val current = repository.buscar(uuid)
            require(current.usuarioAutorId == usuarioActivoId && current.estadoLocal == "BORRADOR_LOCAL") {
                "La Cotizacion cambio mientras se preparaba el envio."
            }
            check(current == before) { "La Cotizacion cambio mientras se preparaba el envio." }
            check(db.cotizacionesQueries.getCotizacionOutbox(uuid).executeAsOneOrNull() == null) {
                "La Cotizacion ya tiene una operacion de envio congelada."
            }
            val timestamp = now().toString()
            db.cotizacionesQueries.freezeCotizacionOutbox(uuid, payload)
            db.cotizacionesQueries.markCotizacionPending(timestamp, uuid, usuarioActivoId)
        }
        return checkNotNull(db.cotizacionesQueries.getCotizacionOutbox(uuid).executeAsOneOrNull()).payload_json
    }

    suspend fun enviar(
        uuid: String,
        usuarioActivoId: Long,
        client: HttpClient,
        baseUrl: String,
        getTokens: () -> Pair<String?, String?>
    ): Resultado = processMutex.withLock { enviarSinMutex(uuid, usuarioActivoId, client, baseUrl, getTokens) }

    suspend fun enviarPendientes(usuarioActivoId: Long, client: HttpClient, baseUrl: String,
                                 getTokens: () -> Pair<String?, String?>): List<Resultado> = processMutex.withLock {
        val current = now().toString()
        db.transaction {
            db.cotizacionesQueries.activateDueCotizacionRetries(MAX_AUTOMATIC_ATTEMPTS, current)
            db.cotizacionesQueries.activateDueCotizacionHeaders(current)
        }
        val uuids = db.cotizacionesQueries.getDueCotizaciones(usuarioActivoId, MAX_AUTOMATIC_ATTEMPTS, current).executeAsList()
        uuids.map { uuid -> enviarSinMutex(uuid, usuarioActivoId, client, baseUrl, getTokens) }
    }

    fun reintentar(uuid: String, usuarioActivoId: Long) {
        val quote = repository.buscar(uuid)
        require(quote.usuarioAutorId == usuarioActivoId) { "La Cotizacion pertenece a otro usuario." }
        val outbox = checkNotNull(db.cotizacionesQueries.getCotizacionOutbox(uuid).executeAsOneOrNull())
        require(outbox.estado_envio in setOf("ERROR_REINTENTABLE", "REQUIERE_AUTENTICACION", "REQUIERE_AUTORIZACION")) {
            "Esta operacion no admite reintento desde el estado actual."
        }
        db.transaction {
            db.cotizacionesQueries.retryCotizacionOutbox(uuid)
            db.cotizacionesQueries.markCotizacionState("PENDIENTE_ENVIO", now().toString(), uuid)
        }
    }

    fun recuperarEnviosInconclusos() {
        db.transaction {
            db.cotizacionesQueries.recoverStuckCotizaciones()
            db.cotizacionesQueries.recoverCotizacionHeaders(now().toString())
        }
    }

    fun outbox(uuid: String) = db.cotizacionesQueries.getCotizacionOutbox(uuid).executeAsOneOrNull()

    private data class Claim(val payload: String, val intentos: Long)

    private fun claim(uuid: String, userId: Long): Claim? = db.transactionWithResult {
        val quote = repository.buscar(uuid)
        require(quote.usuarioAutorId == userId) { "La Cotizacion pertenece a otro usuario." }
        val outbox = db.cotizacionesQueries.getCotizacionOutbox(uuid).executeAsOneOrNull() ?: return@transactionWithResult null
        if (outbox.estado_envio != "PENDIENTE_ENVIO") return@transactionWithResult null
        val timestamp = now().toString()
        db.cotizacionesQueries.markOutboxSending(timestamp, uuid)
        db.cotizacionesQueries.markCotizacionSending(timestamp, uuid, userId)
        val claimed = db.cotizacionesQueries.getCotizacionOutbox(uuid).executeAsOneOrNull() ?: return@transactionWithResult null
        if (claimed.estado_envio != "ENVIANDO") return@transactionWithResult null
        Claim(claimed.payload_json, claimed.intentos)
    }

    private fun accept(uuid: String, userId: Long, payload: String, http: Int, body: JsonObject): Resultado {
        val sent = Json.parseToJsonElement(payload).jsonObject
        val idemExpected = if (http == 201) "CREADA" else "RECUPERADA"
        val responseUuid = body.text("uuidLocal")
        if (responseUuid != uuid || body.text("resultado") != "ACEPTADA" ||
            body.text("resultadoIdempotencia") != idemExpected || body.long("idCotizacion")?.let { it > 0 } != true ||
            body.long("version") == null || body.text("estado").isNullOrBlank() ||
            body.text("revisionComercialId") != sent.text("revisionComercialId") ||
            body.text("fechaComercial") != sent.text("fechaComercial") ||
            body.text("documentoSolicitado") != sent.text("documentoSolicitado")) {
            return mark(uuid, userId, "REQUIERE_REVISION", "CONFIRMACION_NO_COINCIDE",
                "La respuesta de Sync no coincide con el UUID, revision o resultado esperado.", null, null)
        }
        val subtotal = body.decimalText("subtotal")
        val taxes = body.decimalText("impuestos")
        val total = body.decimalText("total")
        val lines = body["renglones"] as? JsonArray
        if (subtotal == null || taxes == null || total == null || lines == null || lines.isEmpty()) {
            return retryable(uuid, userId, checkNotNull(outbox(uuid)?.intentos), "CONFIRMACION_INCOMPLETA",
                "Sync confirmo la operacion sin devolver todos los importes autorizados; reintenta el mismo UUID.")
        }
        val expectedLines = sent["renglones"]?.jsonArray ?: return mark(uuid, userId, "REQUIERE_REVISION",
            "PAYLOAD_INVALIDO", "No se pudo verificar la cantidad de renglones de la captura congelada.", null, null)
        if (lines.size != expectedLines.size || lines.indices.any { index ->
                val actual = lines[index].jsonObject
                val expected = expectedLines[index].jsonObject
                actual.long("productoId") != expected.long("productoId") ||
                    actual.long("presentacionId") != expected.long("presentacionId") ||
                    !actual.sameDecimal("cantidad", expected, "cantidad") ||
                    !actual.sameDecimal("precioUnitario", expected, "precioReferencia") ||
                    !actual.sameDecimal("descuentoPorcentaje", expected, "descuentoSolicitado")
            }) return mark(uuid, userId, "REQUIERE_REVISION", "RENGLONES_NO_COINCIDEN",
            "Los renglones confirmados no coinciden con la captura congelada.", null, null)

        db.transaction {
            val nowText = now().toString()
            db.cotizacionesQueries.acceptCotizacionErp(body.long("idCotizacion")!!, body.long("version")!!,
                body.text("estado")!!, subtotal, taxes, total, nowText, uuid)
            db.cotizacionesQueries.setOutboxSuccess(idemExpected, body.toString(), uuid)
        }
        return Resultado("SINCRONIZADA", if (idemExpected == "CREADA") "Cotizacion creada en ERP." else "Cotizacion existente recuperada por UUID.")
    }

    private fun classifyFailure(uuid: String, userId: Long, attempts: Long, http: Int, body: JsonObject?): Resultado {
        val code = body?.text("resultado") ?: body?.text("origenError") ?: "HTTP_$http"
        val message = body?.text("error")?.take(500) ?: "Sync respondio HTTP $http."
        return when {
            http == 401 && body?.text("origenError") == "ERP" ->
                mark(uuid, userId, "REQUIERE_REVISION", code, message, null, null)
            http == 401 -> mark(uuid, userId, "REQUIERE_AUTENTICACION", code, "La sesion remota fue rechazada. Inicia sesion con el usuario autor.", null, null)
            http == 403 -> mark(uuid, userId, "REQUIERE_AUTORIZACION", code, message, null, null)
            http == 400 || http == 409 || http == 422 -> mark(uuid, userId, "REQUIERE_REVISION", code, message, null, null)
            http == 408 || http == 429 || http >= 500 -> retryable(uuid, userId, attempts, code, message)
            else -> mark(uuid, userId, "REQUIERE_REVISION", code, message, null, null)
        }
    }

    private fun retryable(uuid: String, userId: Long, attempts: Long, code: String, message: String): Resultado {
        val delaySeconds = when (attempts.coerceAtMost(5).toInt()) { 0, 1 -> 30L; 2 -> 120L; 3 -> 600L; 4 -> 1800L; else -> 7200L }
        return mark(uuid, userId, "ERROR_REINTENTABLE", code, message,
            now().plusSeconds(delaySeconds).toString(), "ERROR_REINTENTABLE")
    }

    private fun mark(uuid: String, userId: Long, state: String, code: String, message: String,
                     nextAttempt: String?, result: String?): Resultado {
        db.transaction {
            val timestamp = now().toString()
            db.cotizacionesQueries.setOutboxState(state, code, message.take(1000), timestamp,
                nextAttempt, result ?: state, null, uuid)
            db.cotizacionesQueries.markCotizacionState(state, timestamp, uuid)
        }
        return Resultado(state, message)
    }

    private fun currentResult(uuid: String): Resultado {
        val quote = repository.buscar(uuid)
        return Resultado(quote.estadoLocal, quote.ultimoError ?: "La Cotizacion ya esta siendo procesada o termino.")
    }

    private fun decimal(value: BigDecimal): JsonPrimitive = Json.parseToJsonElement(value.toPlainString()).jsonPrimitive
    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.decimalText(key: String): String? = this[key]?.jsonPrimitive?.takeUnless { it.isString }?.content
    private fun JsonObject.sameDecimal(actualKey: String, other: JsonObject, otherKey: String): Boolean {
        val actual = this[actualKey]?.jsonPrimitive?.takeUnless { it.isString }?.content ?: return false
        val expected = other[otherKey]?.jsonPrimitive?.takeUnless { it.isString }?.content ?: return false
        return runCatching { BigDecimal(actual).compareTo(BigDecimal(expected)) == 0 }.getOrDefault(false)
    }

    private suspend fun enviarSinMutex(uuid: String, userId: Long, client: HttpClient, baseUrl: String,
                                       getTokens: () -> Pair<String?, String?>): Resultado {
        val payload = try { congelar(uuid, userId) }
        catch (e: Exception) { return Resultado("REQUIERE_REVISION", e.message ?: "No se pudo preparar la Cotizacion.") }
        val (access, refresh) = getTokens()
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
            return mark(uuid, userId, "REQUIERE_AUTENTICACION", "SESION_SYNC_AUSENTE",
                "Inicia sesion remota con el usuario autor para enviar esta Cotizacion.", null, null)
        }
        val claimed = claim(uuid, userId) ?: return currentResult(uuid)
        return try {
            val response = client.post("${baseUrl.trimEnd('/')}/api/sync/cotizaciones") {
                contentType(ContentType.Application.Json); setBody(payload)
            }
            val text = response.bodyAsText()
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (response.status.value in setOf(200, 201)) {
                if (json == null) retryable(uuid, userId, claimed.intentos, "RESPUESTA_INVALIDA", "Sync devolvio una respuesta de exito que no se pudo leer.")
                else accept(uuid, userId, payload, response.status.value, json)
            } else classifyFailure(uuid, userId, claimed.intentos, response.status.value, json)
        } catch (e: CancellationException) {
            retryable(uuid, userId, claimed.intentos, "ENVIO_INTERRUPTO", "El envio se interrumpio; reintenta el mismo UUID.")
            throw e
        } catch (e: SyncAuthenticationException) {
            mark(uuid, userId, "REQUIERE_AUTENTICACION", "SESION_SYNC_EXPIRADA", "Inicia sesion remota con el usuario autor.", null, null)
        } catch (_: Exception) {
            retryable(uuid, userId, claimed.intentos, "RED_O_TIMEOUT", "No se confirmo la respuesta; reintenta el mismo UUID.")
        }
    }

    companion object {
        private val processMutex = Mutex()
        private const val MAX_AUTOMATIC_ATTEMPTS = 5L
    }
}
