package com.cyssoftware.effyflow.cotizacion.estados

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CotizacionesSincronizadasScreen(
    userId: Long,
    agentId: Long,
    baseUrl: String,
    getTokens: () -> Pair<String?, String?>,
    saveTokens: (String, String) -> Unit,
    identityStillActive: () -> Boolean,
    onBack: () -> Unit
) {
    val repository = remember { CotizacionEstadosRepository() }
    val devices = remember { DeviceIdentityRepository() }
    val client: HttpClient = remember(userId, baseUrl) {
        cotizacionEstadosClient(getTokens, saveTokens) { "${baseUrl.trimEnd('/')}/api/auth/refresh" }
    }
    val sync = remember(userId, agentId, baseUrl) {
        CotizacionEstadosSync(repository, devices, CotizacionEstadosApi(client, baseUrl),
            userId, agentId, identityStillActive)
    }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Verificando autorización…") }
    var documents by remember { mutableStateOf<List<CotizacionSincronizada>>(emptyList()) }
    var session by remember { mutableStateOf<EstadoSession?>(null) }

    suspend fun refreshLocal() {
        val device = devices.getOrCreate()
        session = repository.latest(userId, agentId, device)
        documents = runCatching { repository.authorizedDocuments(userId, agentId, device) }
            .onFailure { message = it.message ?: "Lectura bloqueada." }.getOrDefault(emptyList())
    }
    fun synchronize(revalidate: Boolean = false) {
        scope.launch {
            loading = true
            message = if (revalidate) "Revalidando autorización…" else "Sincronizando estados…"
            try {
                val result = if (revalidate) sync.revalidate() else sync.synchronize()
                message = result.message
            } catch (_: CancellationException) {
                message = "La sesión local cambió; se descartó la respuesta."
            } catch (e: Exception) {
                message = e.message ?: "No se pudo sincronizar."
            } finally {
                if (identityStillActive()) refreshLocal() else documents = emptyList()
                loading = false
            }
        }
    }
    LaunchedEffect(userId, agentId) { synchronize() }

    Scaffold(topBar = { TopAppBar(title = { Text("Cotizaciones sincronizadas") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Volver") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val s = session
            Text("Estado: ${s?.remoteState ?: "INICIALIZANDO"}", style = MaterialTheme.typography.titleMedium)
            Text("Autorización offline: ${s?.validUntil ?: "no disponible"}")
            Text("Última sincronización: ${s?.lastSyncAt ?: "pendiente"}")
            if (s?.ingestionSuspended == true) Text("Sync reporta saturación temporal (${s.capacityCode ?: "capacidad"}).",
                color = MaterialTheme.colorScheme.error)
            if (s?.remoteState == "REVOCADA") Text("Autorización revocada. Los documentos recibidos fueron retirados.", color = MaterialTheme.colorScheme.error)
            if (s?.bootstrapState == "REQUIERE_REINICIO") Text("La sesión requiere reiniciar su generación.", color = MaterialTheme.colorScheme.error)
            if (s?.errorMessage != null) Text("${s.errorCode}: ${s.errorMessage}", color = MaterialTheme.colorScheme.error)
            Text(message)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !loading, onClick = { synchronize() }) { Text("Sincronizar") }
                OutlinedButton(enabled = !loading, onClick = { synchronize(true) }) { Text("Revalidar") }
                if (loading) CircularProgressIndicator(Modifier.size(28.dp))
            }
            HorizontalDivider()
            if (documents.isEmpty()) Text("No hay documentos autorizados disponibles.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(documents, key = { it.uuid }) { doc ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Cotización ${doc.erpId}", style = MaterialTheme.typography.titleMedium)
                            Text("Estado: ${doc.estado} · versión ${doc.version}")
                            Text("Actualizada: ${doc.updatedAt ?: "sin fecha"}")
                            Text("UUID ${doc.uuid}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
