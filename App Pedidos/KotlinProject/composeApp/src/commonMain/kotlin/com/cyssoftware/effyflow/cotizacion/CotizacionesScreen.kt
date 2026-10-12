package com.cyssoftware.effyflow.cotizacion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CotizacionesScreen(
    repository: CotizacionRepository,
    usuarioId: Long,
    agenteId: Long?,
    baseUrl: String,
    getToken: () -> Pair<String?, String?>,
    saveTokens: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val client = remember(usuarioId, baseUrl) { cotizacionesSyncClient(getToken, saveTokens) { "${baseUrl.trimEnd('/')}/api/auth/refresh" } }
    val outbox = remember(repository) { CotizacionOutboxSync(repository) }
    DisposableEffect(client) { onDispose { client.close() } }
    var revision by remember { mutableStateOf(repository.revisionActiva()) }
    var quotes by remember(usuarioId) { mutableStateOf(repository.borradores(usuarioId)) }
    var selectedQuote by remember(usuarioId) { mutableStateOf<CotizacionLocal?>(null) }
    var message by remember { mutableStateOf("") }
    var isNew by remember(usuarioId) { mutableStateOf(false) }
    var sendingUuid by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository, usuarioId) {
        outbox.recuperarEnviosInconclusos()
        quotes = repository.borradores(usuarioId)
    }

    fun sendQuote(uuid: String, retry: Boolean) {
        scope.launch {
            sendingUuid = uuid
            message = "Preparando envio de la Cotizacion..."
            try {
                if (retry) outbox.reintentar(uuid, usuarioId)
                message = outbox.enviar(uuid, usuarioId, client, baseUrl, getToken).mensaje
            } catch (e: Exception) {
                message = e.message ?: "No se pudo procesar el envio."
            } finally {
                quotes = repository.borradores(usuarioId)
                selectedQuote = selectedQuote?.let { runCatching { repository.buscar(it.uuid) }.getOrNull() }
                sendingUuid = null
            }
        }
    }

    fun sendPending() {
        scope.launch {
            sendingUuid = "*"
            try {
                val results = outbox.enviarPendientes(usuarioId, client, baseUrl, getToken)
                message = if (results.isEmpty()) "No hay envios pendientes vencidos."
                    else results.joinToString("\n") { it.mensaje }
            } catch (e: Exception) {
                message = e.message ?: "No se pudieron procesar las Cotizaciones pendientes."
            } finally {
                quotes = repository.borradores(usuarioId)
                selectedQuote = selectedQuote?.let { runCatching { repository.buscar(it.uuid) }.getOrNull() }
                sendingUuid = null
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Cotizaciones") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Volver") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        message = "Descargando revisión comercial…"
                        runCatching { repository.descargarRevisionActual(client, baseUrl) }
                            .onSuccess { revision = it; message = "Revisión ${it.id} validada y activada." }
                            .onFailure { message = "No se activó la revisión: ${it.message}" }
                    }
                }) { Text("Actualizar revisión") }
                Button(onClick = { isNew = true; selectedQuote = null }) { Text("Nueva cotización") }
                Button(enabled = sendingUuid == null, onClick = { sendPending() }) { Text("Enviar pendientes") }
            }
            revision?.let { Text("Revisión activa: ${it.id} · esquema ${it.versionEsquema}") }
                ?: Text("Sin revisión comercial válida. Descárgala antes de capturar.")
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
            if (isNew || selectedQuote?.estadoLocal == "BORRADOR_LOCAL") {
                CotizacionEditor(repository, revision, usuarioId, agenteId, selectedQuote,
                    onCancel = { isNew = false; selectedQuote = null }, onSaved = { uuid, sendNow ->
                    quotes = repository.borradores(usuarioId); isNew = false; selectedQuote = null
                    message = if (sendNow) "Borrador guardado; preparando envío." else "Borrador guardado localmente."
                    if (sendNow) sendQuote(uuid, false)
                })
            } else if (selectedQuote != null) {
                CotizacionDetalle(selectedQuote!!, repository, onBack = { selectedQuote = null },
                    onSend = { sendQuote(selectedQuote!!.uuid, selectedQuote!!.estadoLocal != "BORRADOR_LOCAL") },
                    sending = sendingUuid == selectedQuote!!.uuid)
            } else {
                Text("Borradores locales", style = MaterialTheme.typography.titleMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quotes, key = { it.uuid }) { quote ->
                        val client = repository.clientePorId(quote.clienteId)
                        ElevatedCard(Modifier.fillMaxWidth().clickable { selectedQuote = quote }) {
                            Column(Modifier.padding(14.dp)) {
                                Text("${quote.documento} · ${client?.razon_social?.takeIf(String::isNotBlank) ?: client?.nombre ?: "Cliente ${quote.clienteId}"} · ${quote.subtotalReferencial}")
                                Text("${quote.estadoLocal} · ${quote.fechaComercial} · revisión ${quote.revisionId.take(8)}…")
                                Text("UUID ${quote.uuid}", style = MaterialTheme.typography.bodySmall)
                                Text("Envio: ${quote.estadoEnvio ?: "SIN_ENVIAR"} · estado ERP: ${quote.estadoComercialErp ?: "sin confirmar"}")
                                quote.idErp?.let { Text("ID ERP $it · version ${quote.versionErp ?: "?"}") }
                                quote.totalErp?.let { Text("Importe autorizado ERP: $it") }
                                quote.ultimoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (quote.estadoLocal == "BORRADOR_LOCAL") {
                                        TextButton(onClick = { selectedQuote = quote }) { Text("Editar") }
                                        Button(enabled = sendingUuid == null, onClick = { sendQuote(quote.uuid, false) }) { Text("Enviar") }
                                    } else if (quote.estadoLocal in setOf("ERROR_REINTENTABLE", "REQUIERE_AUTENTICACION", "REQUIERE_AUTORIZACION")) {
                                        Button(enabled = sendingUuid == null, onClick = { sendQuote(quote.uuid, true) }) { Text("Reintentar") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CotizacionDetalle(quote: CotizacionLocal, repository: CotizacionRepository, onBack: () -> Unit, onSend: () -> Unit, sending: Boolean) {
    val client = repository.clientePorId(quote.clienteId)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cotizacion ${quote.uuid}", style = MaterialTheme.typography.titleMedium)
        Text("${quote.documento} · ${client?.razon_social?.takeIf(String::isNotBlank) ?: client?.nombre ?: "Cliente ${quote.clienteId}"} · fecha ${quote.fechaComercial}")
        Text("Estado local: ${quote.estadoLocal} · envio ${quote.estadoEnvio ?: "SIN_ENVIAR"}")
        Text("Estado comercial ERP: ${quote.estadoComercialErp ?: "sin confirmar"}")
        Text("Revision comercial utilizada: ${quote.revisionId}")
        Text("Importe referencial: ${quote.subtotalReferencial}")
        quote.idErp?.let { Text("ID ERP $it · version ${quote.versionErp ?: "?"}") }
        quote.subtotalErp?.let { Text("Subtotal ERP $it · impuestos ${quote.impuestosErp} · total ERP ${quote.totalErp}") }
        quote.renglones.forEachIndexed { index, line ->
            Text("${index + 1}. Producto ${line.productoId} · cantidad ${line.cantidad} · precio ${line.precioReferencia} · descuento ${line.descuentoSolicitado}%")
        }
        quote.ultimoError?.let { Text("Ultimo error: $it", color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Volver") }
            if (quote.estadoLocal == "BORRADOR_LOCAL") Button(onClick = onSend, enabled = !sending) { Text("Enviar") }
            if (quote.estadoLocal in setOf("ERROR_REINTENTABLE", "REQUIERE_AUTENTICACION", "REQUIERE_AUTORIZACION")) {
                Button(onClick = onSend, enabled = !sending) { Text("Reintentar") }
            }
        }
    }
}
