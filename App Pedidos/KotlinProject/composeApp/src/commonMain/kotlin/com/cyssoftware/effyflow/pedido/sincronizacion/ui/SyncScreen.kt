package com.cyssoftware.effyflow.pedido.sincronizacion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.SyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    viewModel: SyncViewModel = remember { SyncViewModel() },
    onBack: () -> Unit,
) {

    val isLoading = viewModel.isLoading
    val message = viewModel.message
    val progress = viewModel.progress
    val currentCatalog = viewModel.currentCatalog
    val currentPedido = viewModel.currentPedido
    // 🎨 Fondo con gradiente similar a HomeScreen
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 🧭 Barra superior con botón de volver
            TopAppBar(
                title = {
                    Text(
                        "Sincronización",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Volver",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )

            // 🪟 Tarjeta central
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // 🪟 Tarjeta central
                Card(
                    modifier = Modifier
                        .widthIn(min = 340.dp, max = 500.dp)
                        .padding(32.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(32.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // 🧭 Encabezado
                        Text(
                            text = "Sincronización de Datos",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )

                        Text(
                            text = "Administra la sincronización de catálogos y pedidos",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )

                        HorizontalDivider(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .padding(vertical = 12.dp),
                            thickness = DividerDefaults.Thickness,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )

                        // 🔘 Botón Sincronizar Catálogos
                        SyncButton(
                            text = "Sincronizar Catálogos",
                            icon = Icons.Default.CloudSync,
                            color = MaterialTheme.colorScheme.primary,
                            onClick = { viewModel.syncAllCatalogs() },
                            enabled = !isLoading
                        )

                        // 🔘 Botón Sincronizar Pedidos
                        SyncButton(
                            text = "Sincronizar Pedidos",
                            icon = Icons.Default.Download,
                            color = MaterialTheme.colorScheme.secondary,
                            onClick = { viewModel.syncPedidosPendientes() },
                            enabled = !isLoading
                        )
                        if (isLoading) {
                            Spacer(modifier = Modifier.height(12.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = when {
                                    currentCatalog.isNotEmpty() -> "Sincronizando: $currentCatalog"
                                    currentPedido.isNotEmpty() -> "Enviando: $currentPedido"
                                    else -> "Procesando..."
                                },
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth(0.8f)
                                    .height(8.dp),
                                color = ProgressIndicatorDefaults.linearColor,
                                trackColor = ProgressIndicatorDefaults.linearTrackColor,
                                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap
                            )
                        }

                        if (message.isNotEmpty()) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// 🔹 Botón reutilizable con estilo moderno (igual al HomeButton)
@Composable
fun SyncButton(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = color.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
