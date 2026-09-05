package com.cyssoftware.effyflow.pedido.nuevoPedido

import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.PedidoLinea
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object RenglonFiscalSnapshot {
    fun serializar(linea: PedidoLinea): String {
        val impuestos = JsonArray(linea.impuestos.map { impuesto ->
            buildJsonObject {
                impuesto.idImpuesto?.let { put("idImpuesto", it) }
                put("impuesto", impuesto.nombre)
                impuesto.tipoAplicacion?.let { put("tipoAplicacion", it) }
                impuesto.tipoFactor?.let { put("tipoFactor", it) }
                put("tasa", impuesto.tasa)
                put("base", impuesto.base)
                put("importe", impuesto.monto)
                impuesto.cImpuesto?.let { put("cImpuesto", it) }
                impuesto.signo?.let { put("signo", it) }
            }
        })

        return Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("subtotal", linea.subtotal)
                put("totalImpuestos", linea.impuestos.sumOf { it.monto })
                put("total", linea.total)
                put("impuestos", impuestos)
            }
        )
    }
}
