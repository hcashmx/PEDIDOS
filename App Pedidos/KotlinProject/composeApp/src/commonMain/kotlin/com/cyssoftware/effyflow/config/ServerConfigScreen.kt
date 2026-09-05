package com.cyssoftware.effyflow.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ServerConfigScreen(
    repository: AppConfigRepository = remember { AppConfigRepository() },
    required: Boolean,
    onSaved: () -> Unit,
    onCancel: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(repository.getBaseUrl().orEmpty()) }
    var status by remember {
        mutableStateOf(if (url.isBlank()) "Configura el servidor para continuar." else "Configuración actual cargada.")
    }
    var isTesting by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 520.dp)
                .padding(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Servidor",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (required) "Indica la URL base del backend de sincronización." else "Cambia la URL base del backend de sincronización.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL base") },
                    placeholder = { Text("http://servidor:8082") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    status,
                    color = when {
                        status.startsWith("Conectado") -> MaterialTheme.colorScheme.primary
                        status.startsWith("URL inválida") || status.startsWith("No disponible") -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isTesting = true
                                status = "Probando conexión..."
                                status = when (val result = repository.testConnection(url)) {
                                    is ConnectionTestResult.Connected -> "Conectado: ${result.normalizedUrl}"
                                    ConnectionTestResult.InvalidUrl -> "URL inválida. Usa http:// o https://."
                                    ConnectionTestResult.Unavailable -> "No disponible. La configuración guardada no se modifica."
                                }
                                isTesting = false
                            }
                        },
                        enabled = !isTesting,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        if (isTesting) CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                        else Text("Probar conexión")
                    }
                    Button(
                        onClick = {
                            when (val result = repository.saveBaseUrl(url)) {
                                UrlValidationResult.Invalid -> status = "URL inválida. Usa http:// o https://."
                                is UrlValidationResult.Valid -> {
                                    status = "Guardado: ${result.normalizedUrl}"
                                    onSaved()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Text("Guardar")
                    }
                }
                if (!required) {
                    Spacer(Modifier.height(2.dp))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Volver")
                    }
                }
            }
        }
    }
}
