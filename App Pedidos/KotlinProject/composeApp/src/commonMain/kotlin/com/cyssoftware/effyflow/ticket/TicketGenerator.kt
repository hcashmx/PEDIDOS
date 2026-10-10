package com.cyssoftware.effyflow.ticket

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

object TicketGenerator {

    private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    private val dateFormatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "MX"))

    fun generateTicketText(data: TicketFullData): String {
        val sb = StringBuilder()

        // ==== ENCABEZADO PROFESIONAL ====
        sb.appendLine(center("EFFYFLOW S.A. DE C.V.", 42))
        sb.appendLine(center("RFC: ABC123456XYZ", 42))
        sb.appendLine(center("Av. Siempre Viva #123", 42))
        sb.appendLine(center("Tel: 555-123-4567", 42))
        sb.appendLine("------------------------------------------")
        sb.appendLine(center("*** PEDIDO ***", 42))
        sb.appendLine("------------------------------------------")

        val folioCompleto = "${data.pedido.serie_erp ?: data.serie.codigo_serie}-${data.pedido.folio}"
        sb.appendLine("Folio : $folioCompleto")
        sb.appendLine("Fecha : ${formatDate(data.pedido.fecha_creacion)}")
        sb.appendLine("------------------------------------------")

        // ==== CLIENTE ====
        sb.appendLine("Cliente : ${data.pedido.nombreCliente }")
        sb.appendLine("RFC     : ${data.pedido.rfcCliente ?: "N/A"}")
        sb.appendLine("Dir     : ${data.pedido.direccionCliente ?: "N/A"}")
        sb.appendLine("Documento: ${data.pedido.tipo_documento}")
        sb.appendLine("Venta   : ${data.pedido.tipo_venta ?: "Revisar captura antigua"}")
        sb.appendLine("Estado  : ${data.pedido.synced_at}")
        sb.appendLine("Renglones: referencia local")
        sb.appendLine("------------------------------------------")

        // ==== DETALLES ====
        sb.appendLine("CANT  DESCRIPCIÓN            IMPORTE")
        sb.appendLine("------------------------------------------")

        for (item in data.detalles) {
            val cant = item.cantidad_solicitada.toString().padEnd(4)
            val desc = (item.nombreProducto ?: "Producto").take(18).padEnd(18)
            val total = formatCurrency(item.total_linea ?: 0.0).padStart(10)
            sb.appendLine("$cant  $desc$total")
        }

        sb.appendLine("------------------------------------------")

        // ==== TOTALES ====
        val total = data.pedido.total_erp?.let { currencyFormatter.format(java.math.BigDecimal(it)) }
            ?: formatCurrency(data.pedido.total_solicitado ?: 0.0)
        sb.appendLine((if (data.pedido.total_erp != null) "TOTAL ERP" else "TOTAL REFERENCIA LOCAL").padEnd(25) + total.padStart(12))
        sb.appendLine("------------------------------------------")

        // ==== OBSERVACIONES ====
        sb.appendLine("Observaciones:")
        sb.appendLine(data.pedido.observaciones?.ifBlank { "Ninguna" } ?: "Ninguna")
        sb.appendLine()
        sb.appendLine(center("--- Gracias por su preferencia ---", 42))
        sb.appendLine("------------------------------------------")

        return sb.toString()
    }

    // Helpers

    private fun center(text: String, width: Int): String {
        if (text.length >= width) return text
        val left = (width - text.length) / 2
        return " ".repeat(left) + text
    }

    private fun formatCurrency(value: Double): String {
        return currencyFormatter.format(value)
    }

    private fun formatDate(dateString: String?): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = inputFormat.parse(dateString ?: "")
            dateFormatter.format(date!!)
        } catch (e: Exception) {
            dateString ?: "N/A"
        }
    }
}
