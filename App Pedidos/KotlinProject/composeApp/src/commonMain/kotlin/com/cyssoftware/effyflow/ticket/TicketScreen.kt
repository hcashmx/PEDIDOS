package com.cyssoftware.effyflow.ticket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp


/**
 * Pantalla Composable que muestra el ticket y ofrece acciones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketScreen(
    viewModel: TicketViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val actionHandler = getTicketActionHandler() // Obtiene el manejador de acciones de la plataforma

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reimpresión de Ticket") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        },
        content = { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator()
                    }
                    uiState.error != null -> {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    uiState.ticketData != null -> {
                        // Pasamos el texto generado a los botones de acción
                        TicketContent(
                            ticketText = uiState.ticketText,
                            onSave = {
                                actionHandler.save(uiState.ticketText, "pedido-${uiState.ticketData!!.pedido.folio}.txt")
                            },
                            onShare = {
                                actionHandler.share(uiState.ticketText, "Pedido ${uiState.ticketData!!.pedido.folio}")
                            },
                            onPrint = {
                                actionHandler.print(uiState.ticketText, "Pedido ${uiState.ticketData!!.pedido.folio}")
                            }
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun TicketContent(
    ticketText: String,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Área de visualización del ticket
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // Ocupa el espacio disponible
                .background(Color.White, shape = MaterialTheme.shapes.medium)
                .border(1.dp, Color.LightGray, shape = MaterialTheme.shapes.medium)
                .padding(16.dp)
        ) {
            Text(
                text = ticketText,
                fontFamily = FontFamily.Monospace, // Fuente tipo recibo
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Fila de botones de acción
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onSave) {
                Icon(Icons.Default.Save, contentDescription = "Guardar")
                Spacer(Modifier.width(8.dp))
                Text("Guardar")
            }
            Button(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Compartir")
                Spacer(Modifier.width(8.dp))
                Text("Compartir")
            }
            Button(onClick = onPrint) {
                Icon(Icons.Default.Print, contentDescription = "Imprimir")
                Spacer(Modifier.width(8.dp))
                Text("Imprimir")
            }
        }
    }
}