package com.cyssoftware.effyflow.ticket

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Implementación ACTUAL (actual) para Android.
 * Convierte el texto en un PDF para guardar/compartir y lo dibuja
 * en el lienzo de impresión.
 */
actual class TicketActionHandler(private val context: Context) {

    /**
     * Guarda el ticket generando un PDF.
     * Usa la misma lógica de "Compartir" para que el usuario elija dónde guardarlo.
     */
    actual fun save(textContent: String, fileName: String) {
        val pdfFileName = if (fileName.endsWith(".pdf")) fileName else "$fileName.pdf"
        sharePdf(textContent, "Guardar Ticket como...", pdfFileName)
    }

    /**
     * Comparte el ticket generando un PDF y usando la intención ACTION_SEND.
     */
    actual fun share(textContent: String, title: String) {
        sharePdf(textContent, title, "pedido_compartido.pdf")
    }

    /**
     * Envía el trabajo de impresión. El PrintAdapter generará
     * un PDF sobre la marcha para la impresora.
     */
    actual fun print(textContent: String, jobName: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager

        printManager?.print(
            jobName,
            // Este adapter dibuja el texto como PDF para la impresora
            TextPrintDocumentAdapter(textContent),
            PrintAttributes.Builder().build()
        )
    }

    // --- HELPERS ---

    private fun sharePdf(textContent: String, title: String, fileName: String) {
        try {
            // 1. Genera el PDF en el caché
            val file = generatePdfInCache(textContent, fileName)
            // 2. Obtiene una URI segura
            val uri = getUriForFile(file)

            // 3. Crea el Intent para compartir el PDF
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        } catch (e: Exception) {
            println("Error al compartir PDF: ${e.message}")
        }
    }

    /**
     * Genera un archivo PDF a partir del texto y lo guarda en el caché de la app.
     */
    private fun generatePdfInCache(text: String, fileName: String): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        // --- CLAVE: Usar TextPaint con Typeface.MONOSPACE ---
        val paint = TextPaint().apply {
            color = android.graphics.Color.BLACK
            textSize = 10f // Fuente pequeña para recibos
            typeface = Typeface.MONOSPACE // RESPETA TU FORMATO
        }

        val margin = 40f
        val textWidth = canvas.width - (2 * margin)

        // Dibuja el texto completo, manejando saltos de línea
        val staticLayout = StaticLayout.Builder.obtain(
            text, 0, text.length, paint, textWidth.toInt()
        ).build()

        canvas.save()
        canvas.translate(margin, margin) // Aplicar margen
        staticLayout.draw(canvas)
        canvas.restore()

        document.finishPage(page)

        val cacheDir = context.cacheDir
        val file = File(cacheDir, fileName)

        FileOutputStream(file).use {
            document.writeTo(it)
        }

        document.close()
        return file
    }

    /**
     * Obtiene una URI segura (content://) para un archivo usando FileProvider.
     */
    private fun getUriForFile(file: File): Uri {
        val authority = "${context.packageName}.provider"
        return FileProvider.getUriForFile(context, authority, file)
    }
}


/**
 * Adaptador de impresión que toma un String y lo dibuja en el lienzo de impresión
 * (básicamente, crea un PDF para la impresora).
 */
private class TextPrintDocumentAdapter(
    private val text: String
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder("pedido.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        try {
            // Reutilizamos la misma lógica de dibujado de PDF
            val document = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(
                PrintAttributes.MediaSize.ISO_A4.widthMils * 72 / 1000,
                PrintAttributes.MediaSize.ISO_A4.heightMils * 72 / 1000,
                1
            ).create()

            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            val paint = TextPaint().apply {
                color = android.graphics.Color.BLACK
                textSize = 10f
                typeface = Typeface.MONOSPACE // Fuente Monospace
            }

            val margin = 40f
            val textWidth = canvas.width - (2 * margin)

            val staticLayout = StaticLayout.Builder.obtain(
                text, 0, text.length, paint, textWidth.toInt()
            ).build()

            canvas.save()
            canvas.translate(margin, margin)
            staticLayout.draw(canvas)
            canvas.restore()
            document.finishPage(page)

            FileOutputStream(destination.fileDescriptor).use {
                document.writeTo(it)
            }

            document.close()
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))

        } catch (e: IOException) {
            callback.onWriteFailed(e.toString())
        }
    }
}

// ---- Esta función no cambia ----
@Composable
actual fun getTicketActionHandler(): TicketActionHandler {
    val context = LocalContext.current
    return remember(context) {
        TicketActionHandler(context)
    }
}