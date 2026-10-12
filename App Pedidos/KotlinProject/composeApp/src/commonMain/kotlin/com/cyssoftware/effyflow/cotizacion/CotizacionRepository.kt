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
    val precioReferencia: String, val descuentoSolicitado: String = "0"
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

    fun guardarBorrador(usuarioId: Long, agenteId: Long, clienteId: Long, fecha: String, documento: String,
                        observaciones: String?, renglones: List<RenglonCotizacion>, uuid: String? = null) {
        require(usuarioId > 0 && agenteId > 0 && clienteId > 0 && renglones.isNotEmpty())
        require(documento == "FACTURA" || documento == "REMISION")
        val id = uuid ?: uuid4().toString()
        val previousDraft = db.cotizacionesQueries.findCotizacion(id).executeAsOneOrNull()
        val pinnedRevision = if (previousDraft != null) revision(previousDraft.revision_comercial_id) else revisionActiva()
        val revision = checkNotNull(pinnedRevision) {
            "La revisión comercial original no está disponible."
        }
        val localDate = LocalDate.parse(fecha)
        val publishedDate = OffsetDateTime.parse(revision.publicadaEn).atZoneSameInstant(ZoneId.of("America/Mexico_City")).toLocalDate()
        require(!localDate.isBefore(publishedDate)) { "La fecha comercial no puede ser anterior a la publicación de la revisión." }
        val now = Clock.System.now().toString()
        val total = renglones.sumOf { line ->
            val gross = line.cantidad.toBigDecimal() * line.precioReferencia.toBigDecimal()
            gross * (java.math.BigDecimal.ONE - line.descuentoSolicitado.toBigDecimal().divide(java.math.BigDecimal("100")))
        }.toPlainString()
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
            renglones.forEachIndexed { index, line ->
                require(line.cantidad.toBigDecimal() > java.math.BigDecimal.ZERO && line.precioReferencia.toBigDecimal() >= java.math.BigDecimal.ZERO)
                require(line.descuentoSolicitado.toBigDecimal() >= java.math.BigDecimal.ZERO && line.descuentoSolicitado.toBigDecimal() <= java.math.BigDecimal("100"))
                val product = revision.datosComerciales.getValue("productos").jsonArray.firstOrNull {
                    it.jsonObject["id_producto"]?.jsonPrimitive?.longOrNull == line.productoId
                }?.jsonObject
                require(product?.get("activo")?.jsonPrimitive?.booleanOrNull != false) { "El producto no está activo en la revisión." }
                val maxDiscount = product?.get("descuento_maximo")?.jsonPrimitive?.content?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                require(line.descuentoSolicitado.toBigDecimal() <= maxDiscount) { "El descuento excede el máximo local conocido ($maxDiscount%)." }
                if (line.presentacionId != null) {
                    require(revision.datosComerciales.getValue("presentaciones").jsonArray.any { row ->
                        row.jsonObject["id_presentacion"]?.jsonPrimitive?.longOrNull == line.presentacionId &&
                            row.jsonObject["producto_id"]?.jsonPrimitive?.longOrNull == line.productoId
                    }) { "La presentación no corresponde al producto de la revisión." }
                }
                db.cotizacionesQueries.insertCotizacionRenglon(id, index.toLong(), line.productoId, line.presentacionId,
                    line.cantidad, line.precioReferencia, line.descuentoSolicitado)
            }
        }
    }

    fun borradores(usuarioId: Long? = null): List<CotizacionLocal> = db.cotizacionesQueries.listCotizaciones().executeAsList()
        .filter { usuarioId == null || it.usuario_autor_id == usuarioId }.map { toModel(it.uuid_local) }
    fun buscar(uuid: String): CotizacionLocal = toModel(uuid)

    private fun toModel(uuid: String): CotizacionLocal {
        val q = checkNotNull(db.cotizacionesQueries.findCotizacion(uuid).executeAsOneOrNull())
        val lines = db.cotizacionesQueries.getCotizacionRenglones(uuid).executeAsList().map {
            RenglonCotizacion(it.producto_id, it.presentacion_id, it.cantidad, it.precio_referencia, it.descuento_solicitado)
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
        require(clientIds.size == p.datosComerciales.getValue("clientes_lista").jsonArray.size)
        require(productIds.size == p.datosComerciales.getValue("productos").jsonArray.size)
        require(typeIds.size == p.datosComerciales.getValue("tipos_precio").jsonArray.size)
        p.datosComerciales.getValue("precios_lista").jsonArray.forEach { row ->
            require(row.jsonObject.requiredLong("producto_id") in productIds && row.jsonObject.requiredLong("tipo_precio_id") in typeIds)
        }
        p.datosComerciales.getValue("presentaciones").jsonArray.forEach { row ->
            require(row.jsonObject.requiredLong("producto_id") in productIds && row.jsonObject.requiredLong("id_unidad") in unitIds)
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
