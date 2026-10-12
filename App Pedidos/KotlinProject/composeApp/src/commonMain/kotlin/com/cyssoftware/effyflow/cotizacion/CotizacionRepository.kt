package com.cyssoftware.effyflow.cotizacion

import com.benasher44.uuid.uuid4
import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.db.EffyDatabase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.configureSyncBearer
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.math.BigDecimal

@Serializable
data class PaqueteRevisionComercial(
    val id: String, val versionEsquema: Int, val publicadaEn: String,
    val referenciaComercial: String, val huellaSha256: String, val datosComerciales: JsonObject
)

data class CotizacionLocal(
    val uuid: String, val usuarioAutorId: Long, val agenteId: Long, val clienteId: Long,
    val fechaComercial: String, val revisionId: String, val huella: String,
    val documento: String, val observaciones: String?, val estadoLocal: String,
    val subtotalReferencial: String, val renglones: List<RenglonCotizacion>
)

data class RenglonCotizacion(
    val productoId: Long, val presentacionId: Long?, val cantidad: String,
    val precioReferencia: String, val descuentoSolicitado: String = "0",
    val revisionId: String = "", val revisionHuella: String = ""
)

/** Exact Sync DTO shape prepared locally; COT-MOBILE-04C owns serialization and transmission. */
data class CotizacionCapturaPreparada(
    val uuid: String,
    val fechaComercial: String,
    val revisionComercialId: String,
    val revisionHuellaSha256: String,
    val dispositivoId: String,
    val agenteId: Long?,
    val clienteId: Long,
    val documentoSolicitado: String,
    val observaciones: String?,
    val renglones: List<RenglonCapturaPreparada>
)

data class RenglonCapturaPreparada(
    val productoId: Long,
    val presentacionId: Long?,
    val cantidad: BigDecimal,
    val precioReferencia: BigDecimal,
    val descuentoSolicitado: BigDecimal
)

class CotizacionRepository(private val db: EffyDatabase = DatabaseProvider.database) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun dispositivoId(): String {
        val existing = db.app_configQueries.getValue("cotizaciones_dispositivo_id").executeAsOneOrNull()
        if (!existing.isNullOrBlank()) return existing
        val created = "desktop-${uuid4()}"
        db.transaction {
            val current = db.app_configQueries.getValue("cotizaciones_dispositivo_id").executeAsOneOrNull()
            if (current.isNullOrBlank()) db.app_configQueries.upsertValue("cotizaciones_dispositivo_id", created)
        }
        return db.app_configQueries.getValue("cotizaciones_dispositivo_id").executeAsOne()
    }

    suspend fun descargarRevisionActual(client: HttpClient, baseUrl: String): PaqueteRevisionComercial {
        try {
            val response = client.get("${baseUrl.trimEnd('/')}/api/sync/cotizaciones/revisiones-comerciales/actual")
            check(response.status.isSuccess()) { "Sync rechazó la revisión comercial (${response.status.value})." }
            val paquete = response.body<PaqueteRevisionComercial>()
            validar(paquete)
            val downloadedAt = Clock.System.now().toString()
            db.transaction {
                val existing = db.cotizacionesQueries.getRevision(paquete.id).executeAsOneOrNull()
                check(existing == null || existing.huella_sha256 == paquete.huellaSha256) { "El ID ya existe con otra huella." }
                db.cotizacionesQueries.insertRevision(paquete.id, paquete.versionEsquema.toLong(), paquete.publicadaEn,
                    paquete.referenciaComercial, paquete.huellaSha256,
                    json.encodeToString(JsonObject.serializer(), paquete.datosComerciales), downloadedAt)
                check(db.cotizacionesQueries.getRevision(paquete.id).executeAsOneOrNull()?.huella_sha256 == paquete.huellaSha256)
                db.cotizacionesQueries.setActiveRevision(paquete.id)
            }
            return paquete
        } catch (error: Exception) {
            runCatching { db.app_configQueries.upsertValue("revision_comercial_ultimo_error", error.message ?: "Error de sincronización") }
            throw error
        }
    }

    fun revisionActiva(): PaqueteRevisionComercial? {
        val id = db.cotizacionesQueries.activeRevisionId().executeAsOneOrNull() ?: return null
        return revision(id)
    }

    fun revision(id: String): PaqueteRevisionComercial? {
        val row = db.cotizacionesQueries.getRevision(id).executeAsOneOrNull() ?: return null
        val packageData = PaqueteRevisionComercial(row.revision_id, row.version_esquema.toInt(), row.publicada_en,
            row.referencia_comercial, row.huella_sha256, json.parseToJsonElement(row.datos_json).jsonObject)
        return runCatching { validar(packageData); packageData }.getOrNull()
    }

    fun calcularPrecio(revisionId: String, usuarioAgenteId: Long?, clienteId: Long, productoId: Long,
                       presentacionId: Long?, cantidad: String, fecha: String, descuento: String): CotizacionPricing.Result {
        val packageData = checkNotNull(revision(revisionId)) { "La revisión del borrador no está disponible." }
        return CotizacionPricing(packageData.datosComerciales).calculate(clienteId, usuarioAgenteId, productoId,
            presentacionId, cantidad.toBigDecimal(), LocalDate.parse(fecha), descuento.toBigDecimal())
    }

    fun guardarBorrador(usuarioId: Long, agenteId: Long, clienteId: Long, fecha: String, documento: String,
                        observaciones: String?, renglones: List<RenglonCotizacion>, uuid: String? = null) {
        require(usuarioId > 0 && agenteId > 0 && clienteId > 0 && renglones.isNotEmpty())
        require(documento == "FACTURA" || documento == "REMISION")
        val id = uuid ?: uuid4().toString()
        val previousDraft = db.cotizacionesQueries.findCotizacion(id).executeAsOneOrNull()
        if (previousDraft != null) {
            require(previousDraft.usuario_autor_id == usuarioId) { "La Cotización pertenece a otro usuario." }
            require(previousDraft.agente_id == agenteId) { "La Cotización conserva el agente que la capturó." }
            require(previousDraft.estado_local in listOf("BORRADOR_LOCAL", "ERROR_REINTENTABLE")) { "Sólo se editan borradores no enviados." }
        }
        val pinnedRevision = if (previousDraft != null) revision(previousDraft.revision_comercial_id) else revisionActiva()
        val revision = checkNotNull(pinnedRevision) {
            "La revisión comercial original no está disponible."
        }
        val localDate = LocalDate.parse(fecha)
        val publishedDate = OffsetDateTime.parse(revision.publicadaEn).atZoneSameInstant(ZoneId.of("America/Mexico_City")).toLocalDate()
        require(!localDate.isBefore(publishedDate)) { "La fecha comercial no puede ser anterior a la publicación de la revisión." }
        val now = Clock.System.now().toString()
        val calculated = renglones.map { line ->
            val result = CotizacionPricing(revision.datosComerciales).calculate(clienteId, agenteId, line.productoId,
                line.presentacionId, line.cantidad.toBigDecimal(), localDate, line.descuentoSolicitado.toBigDecimal())
            line.copy(precioReferencia = result.offeredUnitPrice.toPlainString(), revisionId = revision.id, revisionHuella = revision.huellaSha256) to result
        }
        val total = calculated.fold(java.math.BigDecimal.ZERO) { sum, item -> sum + item.second.netLineAmount }.toPlainString()
        db.transaction {
            val previous = previousDraft
            if (previous == null) {
                db.cotizacionesQueries.insertCotizacion(id, usuarioId, agenteId, clienteId, fecha, now, revision.id,
                    revision.huellaSha256, documento, observaciones, "BORRADOR_LOCAL", null, now, null, null, total)
            } else {
                require(previous.usuario_autor_id == usuarioId) { "La Cotización pertenece a otro usuario." }
                require(previous.estado_local in listOf("BORRADOR_LOCAL", "ERROR_REINTENTABLE")) { "Sólo se editan borradores no enviados." }
                val original = db.cotizacionesQueries.getRevision(previous.revision_comercial_id).executeAsOneOrNull()
                require(original?.huella_sha256 == previous.revision_huella_sha256) { "La revisión original no está disponible." }
                db.cotizacionesQueries.updateCotizacion(clienteId, fecha, documento, observaciones, "BORRADOR_LOCAL", now, total, id, usuarioId)
            }
            db.cotizacionesQueries.deleteCotizacionRenglones(id)
            calculated.forEachIndexed { index, (line, _) ->
                db.cotizacionesQueries.insertCotizacionRenglon(id, index.toLong(), line.productoId, line.presentacionId,
                    line.cantidad, line.precioReferencia, line.descuentoSolicitado, revision.id, revision.huellaSha256)
            }
        }
    }

    fun borradores(usuarioId: Long? = null): List<CotizacionLocal> = db.cotizacionesQueries.listCotizaciones().executeAsList()
        .filter { usuarioId == null || it.usuario_autor_id == usuarioId }.map { toModel(it.uuid_local) }
    fun buscar(uuid: String): CotizacionLocal = toModel(uuid)

    fun prepararCaptura(uuid: String): CotizacionCapturaPreparada {
        val quote = buscar(uuid)
        require(quote.renglones.isNotEmpty()) { "La Cotización no tiene renglones." }
        require(quote.renglones.all { it.revisionId == quote.revisionId && it.revisionHuella == quote.huella }) {
            "Los renglones no pertenecen a la revisión histórica de la Cotización."
        }
        return CotizacionCapturaPreparada(
            uuid = quote.uuid,
            fechaComercial = quote.fechaComercial,
            revisionComercialId = quote.revisionId,
            revisionHuellaSha256 = quote.huella,
            dispositivoId = dispositivoId(),
            agenteId = quote.agenteId,
            clienteId = quote.clienteId,
            documentoSolicitado = quote.documento,
            observaciones = quote.observaciones,
            renglones = quote.renglones.map {
                RenglonCapturaPreparada(it.productoId, it.presentacionId, it.cantidad.toBigDecimal(),
                    it.precioReferencia.toBigDecimal(), it.descuentoSolicitado.toBigDecimal())
            }
        )
    }

    private fun toModel(uuid: String): CotizacionLocal {
        val q = checkNotNull(db.cotizacionesQueries.findCotizacion(uuid).executeAsOneOrNull())
        val lines = db.cotizacionesQueries.getCotizacionRenglones(uuid).executeAsList().map {
            RenglonCotizacion(it.producto_id, it.presentacion_id, it.cantidad, it.precio_referencia,
                it.descuento_solicitado, it.revision_comercial_id, it.revision_huella_sha256)
        }
        return CotizacionLocal(q.uuid_local, q.usuario_autor_id, q.agente_id, q.cliente_id, q.fecha_comercial,
            q.revision_comercial_id, q.revision_huella_sha256, q.documento_solicitado, q.observaciones,
            q.estado_local, q.subtotal_referencial, lines)
    }

    private fun validar(p: PaqueteRevisionComercial) {
        require(p.id.matches(Regex("[0-9a-fA-F-]{36}"))) { "ID de revisión inválido." }
        require(p.versionEsquema == 3 && p.datosComerciales["version_esquema"]?.jsonPrimitive?.int == 3) { "Versión de revisión no soportada." }
        require(p.huellaSha256.matches(Regex("[0-9a-f]{64}")) && p.referenciaComercial == "sha256:${p.huellaSha256}")
        OffsetDateTime.parse(p.publicadaEn)
        val required = listOf("clientes_lista", "productos", "unidades", "tipos_precio", "precios_lista", "precios_volumen",
            "presentaciones", "promociones", "promociones_productos", "promociones_listas", "impuestos_producto", "kits_composicion", "configuracion_comercial")
        required.forEach { require(p.datosComerciales[it] is JsonArray) { "Falta el conjunto $it." } }
        require(p.datosComerciales.getValue("configuracion_comercial").jsonArray.isNotEmpty())
        val clientIds = p.datosComerciales.getValue("clientes_lista").jsonArray.map { it.jsonObject.requiredLong("id_cliente") }.toSet()
        val productIds = p.datosComerciales.getValue("productos").jsonArray.map { it.jsonObject.requiredLong("id_producto") }.toSet()
        val unitIds = p.datosComerciales.getValue("unidades").jsonArray.map { it.jsonObject.requiredLong("id_unidad") }.toSet()
        val typeIds = p.datosComerciales.getValue("tipos_precio").jsonArray.map { it.jsonObject.requiredLong("id_tipo_precio") }.toSet()
        val promotionIds = p.datosComerciales.getValue("promociones").jsonArray.map { it.jsonObject.requiredLong("id") }.toSet()
        require(clientIds.size == p.datosComerciales.getValue("clientes_lista").jsonArray.size)
        require(productIds.size == p.datosComerciales.getValue("productos").jsonArray.size)
        require(unitIds.size == p.datosComerciales.getValue("unidades").jsonArray.size)
        require(typeIds.size == p.datosComerciales.getValue("tipos_precio").jsonArray.size)
        require(promotionIds.size == p.datosComerciales.getValue("promociones").jsonArray.size)
        p.datosComerciales.getValue("clientes_lista").jsonArray.forEach { row ->
            require(row.jsonObject.requiredLong("numero_precio_id") in typeIds) { "Un cliente referencia una lista inexistente." }
        }
        p.datosComerciales.getValue("productos").jsonArray.forEach { row ->
            require(row.jsonObject.requiredLong("id_unidad") in unitIds) { "Un producto referencia una unidad inexistente." }
        }
        p.datosComerciales.getValue("precios_lista").jsonArray.forEach { row ->
            require(row.jsonObject.requiredLong("producto_id") in productIds && row.jsonObject.requiredLong("tipo_precio_id") in typeIds)
        }
        p.datosComerciales.getValue("presentaciones").jsonArray.forEach { row ->
            require(row.jsonObject.requiredLong("producto_id") in productIds && row.jsonObject.requiredLong("id_unidad") in unitIds)
        }
        p.datosComerciales.getValue("precios_volumen").jsonArray.forEach { row ->
            val rule = row.jsonObject
            require(rule.requiredLong("producto_id") in productIds && rule.requiredLong("tipo_precio_destino_id") in typeIds)
            rule["tipo_precio_origen_id"]?.jsonPrimitive?.longOrNull?.let { require(it in typeIds) }
        }
        p.datosComerciales.getValue("promociones_productos").jsonArray.forEach { row ->
            val scope = row.jsonObject
            require(scope.requiredLong("promocion_id") in promotionIds && scope.requiredLong("producto_id") in productIds)
        }
        p.datosComerciales.getValue("promociones_listas").jsonArray.forEach { row ->
            val scope = row.jsonObject
            require(scope.requiredLong("promocion_id") in promotionIds && scope.requiredLong("tipo_precio_id") in typeIds)
        }
        p.datosComerciales.getValue("impuestos_producto").jsonArray.forEach { row ->
            require(row.jsonObject.requiredLong("id_producto") in productIds)
        }
        p.datosComerciales.getValue("kits_composicion").jsonArray.forEach { row ->
            require(row.jsonObject.requiredLong("id_producto_kit") in productIds && row.jsonObject.requiredLong("id_producto_componente") in productIds)
        }
        require(sha256Hex(canonical(p.datosComerciales).encodeToByteArray()) == p.huellaSha256) { "La huella SHA-256 no coincide." }
    }

    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonical(element.getValue(it)) }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        else -> json.encodeToString(JsonElement.serializer(), element)
    }
}

private fun JsonObject.requiredLong(field: String): Long = checkNotNull(this[field]?.jsonPrimitive?.longOrNull) { "Referencia $field faltante." }

expect fun sha256Hex(bytes: ByteArray): String

fun cotizacionesSyncClient(getToken: () -> Pair<String?, String?>, saveTokens: (String, String) -> Unit,
                           refreshUrl: () -> String): HttpClient = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    configureSyncBearer(getToken, saveTokens, refreshUrl)
}
