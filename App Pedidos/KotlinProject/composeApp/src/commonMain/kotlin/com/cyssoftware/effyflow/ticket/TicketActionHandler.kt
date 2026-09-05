package com.cyssoftware.effyflow.ticket

import androidx.compose.runtime.Composable

/**
 * Define la interfaz esperada (expect) para las acciones de ticket
 * que dependen de la plataforma (Android, iOS, Desktop).
 */
expect class TicketActionHandler {

    /**
     * Guarda el contenido de texto en un archivo (ej. .txt o .pdf si se genera).
     * @param textContent El texto a guardar.
     * @param fileName El nombre sugerido para el archivo, ej. "pedido-123.txt".
     */
    fun save(textContent: String, fileName: String)

    /**
     * Abre el diálogo nativo para compartir contenido.
     * @param textContent El texto a compartir.
     * @param title El título para el diálogo de compartir (usado en Android).
     */
    fun share(textContent: String, title: String)

    /**
     * Envía el contenido de texto al servicio de impresión nativo.
     * @param textContent El texto a imprimir.
     * @param jobName Un nombre para el trabajo de impresión, ej. "Impresión Pedido 123".
     */
    fun print(textContent: String, jobName: String)
}

/**
 * Función Composable para obtener la implementación real (actual)
 * del manejador de acciones.
 */
@Composable
expect fun getTicketActionHandler(): TicketActionHandler