package com.cyssoftware.effyflow.pedido.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PedidoScreen(
    onBack: () -> Unit, // 👈 nuevo callback para volver
    onNuevoPedido: () -> Unit,
    onCotizaciones: () -> Unit = {},
    onCotizacionesSincronizadas: () -> Unit = {},
    onPedidosDia: () -> Unit,
    onSincronizacion: () -> Unit,
    onReimpresion: () -> Unit
) {
    // 🎨 Fondo con gradiente
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
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 🧭 Barra superior con botón de volver
            TopAppBar(
                title = {
                    Text(
                        "Módulo de Pedidos",
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
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .widthIn(min = 340.dp, max = 500.dp)
                        .padding(16.dp),
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
                        Text(
                            "Pedidos",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "Selecciona una acción para continuar",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )

                        HorizontalDivider(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .padding(vertical = 8.dp),
                            thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )

                        PedidoButton(
                            text = "Nuevo Pedido",
                            icon = Icons.Default.Description,
                            color = MaterialTheme.colorScheme.primary,
                            onClick = onNuevoPedido
                        )

                        PedidoButton(text = "Cotizaciones", icon = Icons.Default.Description,
                            color = Color(0xFF6A4C93), onClick = onCotizaciones)

                        PedidoButton(text = "Cotizaciones sincronizadas", icon = Icons.Default.CloudSync,
                            color = Color(0xFF455A64), onClick = onCotizacionesSincronizadas)

                        PedidoButton(
                            text = "Pedidos del Día",
                            icon = Icons.Default.ListAlt,
                            color = Color(0xFF009688),
                            onClick = onPedidosDia
                        )

                        PedidoButton(
                            text = "Sincronización",
                            icon = Icons.Default.CloudSync,
                            color = Color(0xFF3F51B5),
                            onClick = onSincronizacion
                        )

                        /*PedidoButton(
                            text = "Reimpresión",
                            icon = Icons.Default.Print,
                            color = Color(0xFFFF9800),
                            onClick = onReimpresion
                        )*/
                    }
                }
            }
        }
    }
}

// 🔹 Componente reutilizable de botón con estilo moderno
@Composable
fun PedidoButton(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
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
