package com.cyssoftware.effyflow.pedido.sincronizacion.viewModel

import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.pedido.sincronizacion.dto.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant

/** Durable outbox: freeze before HTTP, never change the capture on a technical retry. */
class PedidoComercialSync(private val db: EffyDatabase) {
    suspend fun enviar(id: Long, post: suspend (String) -> Pair<Int, JsonObject>): String {
        val capture = try {
            payload(id)
        } catch (e: IllegalArgumentException) {
            errorValidacion(id, e.message ?: "Revisar captura")
            return e.message ?: "Revisar captura"
        }
        return try {
            enviando(id)
            val (http, body) = post(capture)
            resultado(id, http, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncAuthenticationException) {
            errorValidacion(id, "Sesion Sync expirada: iniciar sesion y revisar antes de reenviar")
            "Sesion Sync expirada: iniciar sesion nuevamente"
        } catch (e: Exception) {
            errorRed(id)
            "Error reintentable: red/timeout. Se conserva la captura"
        }
    }
    fun payload(id: Long): String {
        val pedido = db.pedidosQueries.findById(id).executeAsOne()
        pedido.payload_comercial?.let { return it }
        require(pedido.tipo_documento in listOf("FACTURA", "REMISION")) { "Documento invalido: FACTURA o REMISION" }
        require(pedido.tipo_venta in listOf("CONTADO", "CREDITO")) { "Tipo de venta ausente/invalido; requiere revision manual de captura legacy" }
        require(runCatching { Instant.parse(pedido.fecha_creacion) }.isSuccess) {
            "Fecha original sin offset; requiere recuperacion documentada de zona, no se reconstruye automaticamente"
        }
        require(runCatching { UUID.fromString(pedido.uuid_local) }.isSuccess) { "UUID local ausente/invalido; revisar manualmente" }
        val detalles = db.detalle_pedidosQueries
            .getDetallesPorPedido(pedido.id_pedido)
            .executeAsList()
            .sortedBy { it.id_detalle_pedido }

        val pedidoDTO = PedidoDTO(
            id_pedido = pedido.id_pedido,
            cliente_id = pedido.cliente_id,
            agente_ventas_id = pedido.agente_ventas_id,
            usuario_creo_id = pedido.usuario_creo_id,
            usuario_autorizo_id = pedido.usuario_autorizo_id,
            serie_id = pedido.serie_id,
            folio = pedido.folio,
            fecha_creacion = pedido.fecha_creacion,
            fecha_autorizacion = pedido.fecha_autorizacion,
            fecha_envio_almacen = pedido.fecha_envio_almacen,
            fecha_cierre = pedido.fecha_cierre,
            plazo = pedido.plazo,
            estado = pedido.estado,
            observaciones = pedido.observaciones,
            venta_id = pedido.venta_id,
            total_solicitado = pedido.total_solicitado,
            total_autorizado = pedido.total_autorizado,
            total_facturado = pedido.total_facturado,
            uuid_local = pedido.uuid_local,
            uuid_server = pedido.uuid_server,
            updated_at = pedido.updated_at,
            synced_at = pedido.synced_at,
            tipo_documento = pedido.tipo_documento,
            ubicacion_id = pedido.ubicacion_id,
            tipo_venta = pedido.tipo_venta
        )

        val detallesDTO = detalles.map {
            DetallePedidoDTO(
                id_detalle_pedido = it.id_detalle_pedido,
                pedido_id = it.pedido_id,
                producto_id = it.producto_id,
                presentacion_id = it.presentacion_id,
                almacen_id = it.almacen_id,
                cantidad_solicitada = it.cantidad_solicitada,
                cantidad_autorizada = it.cantidad_autorizada,
                cantidad_reservada = it.cantidad_reservada,
                cantidad_surtida = it.cantidad_surtida,
                cantidad_cancelada = it.cantidad_cancelada,
                precio_unitario = it.precio_unitario,
                descuento = it.descuento,
                impuestos = it.impuestos,
                total_linea = it.total_linea,
                estado_linea = it.estado_linea,
                updated_at = it.updated_at,
            )
        }

        val payload = PedidoConDetallesDTO(
            pedido = pedidoDTO,
            detalles = detallesDTO
        )
        require(detalles.isNotEmpty()) { "Pedido sin renglones" }
        require(detalles.all { (it.almacen_id ?: 0) > 0 } && detalles.map { it.almacen_id }.distinct().size == 1) {
            "Selecciona un solo almacen valido para todo el Pedido"
        }
        val frozen = decimalPayload(Json.encodeToJsonElement(payload)).toString()
        db.pedidosQueries.freezePayload(frozen, id)
        return db.pedidosQueries.findById(id).executeAsOne().payload_comercial!!
    }

    fun enviando(id: Long) = db.pedidosQueries.setResultadoComercial("SINCRONIZANDO", null, id)
    fun errorRed(id: Long) = db.pedidosQueries.setResultadoComercial("ERROR_REINTENTABLE",
        "{\"error\":\"Red/timeout: reintentar la misma captura\"}", id)
    fun errorValidacion(id: Long, mensaje: String) = db.pedidosQueries.setResultadoComercial(
        "ERROR_NO_REINTENTABLE", buildJsonObject { put("error", mensaje) }.toString(), id)

    fun resultado(id: Long, http: Int, body: JsonObject): String {
        fun value(key: String) = body[key]?.jsonPrimitive?.contentOrNull
        val result = value("resultado")
        if (http in 200..299 && result == "ACEPTADO") {
            val remote = value("idPedido")?.toLongOrNull()
            val folio = value("folio")?.toLongOrNull()
            val state = value("estadoId")?.toLongOrNull()
            val serieId = value("serieId")?.toLongOrNull()
            val total = value("total")
            val fecha = value("fechaRecepcion")
            val pedido = db.pedidosQueries.findById(id).executeAsOne()
            if (remote == null || remote <= 0 || folio == null || state == null || serieId == null ||
                total == null || runCatching { BigDecimal(total) }.isFailure || fecha == null ||
                value("uuidLocal") != pedido.uuid_local || value("estado").isNullOrBlank() || value("serie").isNullOrBlank()) {
                db.pedidosQueries.setResultadoComercial("ERROR_REINTENTABLE", body.toString(), id)
                return "Respuesta aceptada incompleta; recuperar con mismo UUID"
            }
            db.pedidosQueries.acceptComercial(remote.toString(), state, folio, serieId,
                value("serie"), total, fecha, value("version"), body.toString(), id)
            return "Sincronizado: ERP ${value("serie")}/$folio, total $total"
        }
        val status = when {
            http == 409 && result == "DIFERENCIAS_COMERCIALES" -> "PENDIENTE_REVISION"
            http == 409 -> "CONFLICTO"
            http == 408 || http == 429 || http >= 500 -> "ERROR_REINTENTABLE"
            http in 200..299 -> "ERROR_REINTENTABLE"
            else -> "ERROR_NO_REINTENTABLE"
        }
        db.pedidosQueries.setResultadoComercial(status, body.toString(), id)
        return when(status) {
            "PENDIENTE_REVISION" -> "Diferencias comerciales: revisar detalle del Pedido; no fue aceptado"
            "CONFLICTO" -> "Conflicto UUID/captura: revision manual, no duplicar Pedido"
            else -> "$status: ${value("error") ?: "HTTP $http"} (${value("origenError") ?: "Sync"})"
        }
    }

    /** ERP/Sync accept JSON decimals. Keep six decimal places, without binary tails. */
    private fun decimalPayload(element: JsonElement): JsonElement = when(element) {
        is JsonArray -> JsonArray(element.map { decimalPayload(it) })
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            when {
                key == "impuestos" && value is JsonPrimitive && value.isString ->
                    JsonPrimitive(decimalPayload(Json.parseToJsonElement(value.content)).toString())
                key in monetaryKeys && value is JsonPrimitive && value != JsonNull ->
                    Json.parseToJsonElement(BigDecimal(value.content).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
                else -> decimalPayload(value)
            }
        })
        else -> element
    }
    companion object {
        private val monetaryKeys = setOf("precio_unitario", "descuento", "total_linea", "total_solicitado",
            "total_autorizado", "total_facturado", "subtotal", "totalImpuestos", "total", "base", "importe", "tasa")
    }
}

fun diferenciasComerciales(resultado: String?): List<String> {
    val body = runCatching { Json.parseToJsonElement(resultado ?: "").jsonObject }.getOrNull() ?: return emptyList()
    val preview = body["detalleErp"] as? JsonObject ?: return listOfNotNull(body["error"]?.jsonPrimitive?.contentOrNull)
    val diferencias = preview["diferencias"] as? JsonArray ?: return listOfNotNull(
        body["error"]?.jsonPrimitive?.contentOrNull, preview["error"]?.jsonPrimitive?.contentOrNull)
    return diferencias.map { element ->
        val d = element.jsonObject
        fun v(k: String) = d[k]?.jsonPrimitive?.contentOrNull ?: "sin referencia"
        "Renglon ${v("renglon")}: precio local ${v("precioApp")} / ERP ${v("precioErp")}; " +
            "descuento local ${v("descuentoApp")} / ERP ${v("descuentoErp")}; " +
            "subtotal local ${v("subtotalApp")} / ERP ${v("subtotalErp")}; " +
            "impuestos local ${v("impuestosApp")} / ERP ${v("impuestosErp")}; " +
            "total local ${v("totalApp")} / ERP ${v("totalErp")}. ${v("motivo")}. " +
            "Promocion local ${v("promocionApp")} / ERP ${v("promocionErp")}"
    } + listOf("Total definitivo propuesto ERP: ${preview["total"] ?: "sin dato"}")
}
