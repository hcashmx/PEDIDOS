package com.cyssoftware.effyflow.ticket

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.printing.PDFPrintable
import org.apache.pdfbox.printing.Scaling
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.print.PrinterJob
import java.io.File
import javax.print.PrintServiceLookup
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Implementación real (actual) para Desktop (JVM).
 * Utiliza PDFBox 3.x para generar PDF y enviarlo a imprimir.
 */
actual class TicketActionHandler {

    /**
     * Abre un diálogo para guardar el ticket como un archivo PDF.
     */
    actual fun save(textContent: String, fileName: String) {
        val fileChooser = JFileChooser().apply {
            dialogTitle = "Guardar Ticket como PDF"
            selectedFile = File(fileName.replaceAfterLast(".", "pdf").replace(".txt", ".pdf"))
            fileFilter = FileNameExtensionFilter("Documento PDF (*.pdf)", "pdf")
        }

        val result = fileChooser.showSaveDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            var file = fileChooser.selectedFile
            if (!file.name.lowercase().endsWith(".pdf")) {
                file = File(file.parentFile, file.name + ".pdf")
            }

            try {
                generatePdfDocument(textContent).use { document ->
                    document.save(file)
                }
                println("PDF guardado en: ${file.absolutePath}")

            } catch (e: Exception) {
                println("Error al guardar PDF: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * En Desktop, “Compartir” = copiar el texto al portapapeles.
     */
    actual fun share(textContent: String, title: String) {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(textContent), null)

            println("Ticket copiado al portapapeles.")

        } catch (e: Exception) {
            println("Error al copiar al portapapeles: ${e.message}")
        }
    }

    /**
     * Genera un PDF en memoria y lo envía al servicio de impresión.
     * PDFBox 3.x usa PDFPrintable (PDFPrinter ya no existe).
     */
    actual fun print(textContent: String, jobName: String) {
        try {
            val printService = PrintServiceLookup.lookupDefaultPrintService()
            if (printService == null) {
                println("No se encontró servicio de impresión.")
                return
            }

            generatePdfDocument(textContent).use { document ->
                val job = PrinterJob.getPrinterJob()
                job.printService = printService

                val printable = PDFPrintable(document, Scaling.SHRINK_TO_FIT)
                job.setPrintable(printable)

                job.print()

                println("Trabajo de impresión enviado.")
            }

        } catch (e: Exception) {
            println("Error al imprimir: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Crea un documento PDF en memoria a partir del String del ticket.
     */
    private fun generatePdfDocument(textContent: String): PDDocument {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val font = PDType1Font(Standard14Fonts.FontName.COURIER)
        val fontSize = 10f
        val leading = 1.2f * fontSize

        PDPageContentStream(document, page).use { contentStream ->
            contentStream.beginText()
            contentStream.setFont(font, fontSize)
            contentStream.setLeading(leading)
            contentStream.newLineAtOffset(50f, 750f)

            textContent.lines().forEach { line ->
                contentStream.showText(line)
                contentStream.newLine()
            }

            contentStream.endText()
        }

        return document
    }
}

@Composable
actual fun getTicketActionHandler(): TicketActionHandler {
    return remember { TicketActionHandler() }
}
