package com.cyssoftware.effyflow.pedido.pedidosDia.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyssoftware.effyflow.db.GetDetalleConProductoPorPedidoId
import com.cyssoftware.effyflow.db.GetPedidosFiltrados
import com.cyssoftware.effyflow.pedido.pedidosDia.viewmodel.PedidoFilters
import com.cyssoftware.effyflow.pedido.pedidosDia.viewmodel.PedidosDiaViewModel
import java.util.TimeZone
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.diferenciasComerciales

// ------------------------------------------------------------------------------------------
// PANTALLA PRINCIPAL
// ------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PedidosDiaScreen(
    viewModel: PedidosDiaViewModel,
    onPrintTicket: (Long) -> Unit,
    onBack: () -> Unit,
    onRefreshStates: (() -> Unit)? = null,
    refreshingStates: Boolean = false,
    stateSyncMessage: String = "",
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.pedidoSeleccionado == null)
                            "Pedidos del Día"
                        else
                            "Detalle del Pedido",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (state.pedidoSeleccionado != null) {
                        IconButton(onClick = { viewModel.cerrarDetalle() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                        }
                    }else{
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Volver",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                actions = {
                    onRefreshStates?.let { refresh ->
                        TextButton(onClick = refresh, enabled = !refreshingStates) {
                            Text(if (refreshingStates) "Actualizando..." else "Actualizar estados")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            if (stateSyncMessage.isNotBlank()) {
                Text(stateSyncMessage, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp))
            }

            // --- Lógica de Navegación (Lista / Detalle) ---
            if (state.pedidoSeleccionado == null) {
                FilterBar(
                    filters = state.currentFilters,
                    onApplyFilters = { newFilters -> viewModel.applyFilters(newFilters) }
                )

                Spacer(Modifier.height(8.dp))

                PedidosList(
                    pedidos = state.pedidos,
                    onPedidoClick = { pedido -> viewModel.seleccionarPedido(pedido) }
                )
            } else {
                PedidoDetail(
                    pedido = state.pedidoSeleccionado!!,
                    detalle = state.detalleSeleccionado,
                    onPrintTicket = onPrintTicket,
                    onRetry = { viewModel.reintentarRevision() }
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// VISTA: BARRA DE FILTROS
// ------------------------------------------------------------------------------------------

@Composable
fun FilterBar(
    filters: PedidoFilters,
    onApplyFilters: (PedidoFilters) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (filters.fechaInicio == null)
                        "Mostrando: Todos los pedidos"
                    else
                        "Mostrando: ${filters.fechaInicio} -> ${filters.fechaFin ?: "..."}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Estado: ${statusFilterLabel(filters.estatus)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.FilterList, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Filtrar")
            }
        }
    }

    if (showDialog) {
        FilterDialog(
            initialFilters = filters,
            onDismiss = { showDialog = false },
            onApply = {
                onApplyFilters(it)
                showDialog = false
            }
        )
    }
}

fun statusFilterLabel(estatus: String?): String = when (estatus) {
    null, "" -> "Todos los estados"
    "PENDIENTE_SYNC" -> "Pendiente de sincronizar"
    "SYNCHRONIZED" -> "Sincronizado"
    "SINCRONIZANDO" -> "Sincronizando"
    "PENDIENTE_REVISION" -> "Pendiente de revision por diferencias"
    "CONFLICTO" -> "Conflicto: revision manual"
    "ERROR_REINTENTABLE" -> "Error reintentable"
    "ERROR_NO_REINTENTABLE" -> "Error no reintentable"
    else -> estatus
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterDialog(
    initialFilters: PedidoFilters,
    onDismiss: () -> Unit,
    onApply: (PedidoFilters) -> Unit,
) {
    var cliente by remember { mutableStateOf(initialFilters.clienteNombre ?: "") }
    var estatus by remember { mutableStateOf(initialFilters.estatus ?: "") }
    var producto by remember { mutableStateOf(initialFilters.productoNombre ?: "") }
    var fechaInicio by remember { mutableStateOf(initialFilters.fechaInicio ?: "") }
    var fechaFin by remember { mutableStateOf(initialFilters.fechaFin ?: "") }


    val estatusOptions = listOf(
        "" to "Todos los estados",
        "PENDIENTE_SYNC" to "Sincronización pendiente", // Usar la clave PENDIENTE_SYNC para el estado Pendiente
        "SYNCHRONIZED" to "Sincronizado",
        "SINCRONIZANDO" to "Sincronizando",
        "PENDIENTE_REVISION" to "Pendiente de revision por diferencias",
        "CONFLICTO" to "Conflicto: revision manual",
        "ERROR_REINTENTABLE" to "Error reintentable",
        "ERROR_NO_REINTENTABLE" to "Error no reintentable"
    )

    // --- Nuevo estado para el Dropdown de Estatus ---
    var expanded by remember { mutableStateOf(false) }
    // --- Nombre Visible Seleccionado ---
    val selectedStatusName = estatusOptions.find { it.first == estatus }?.second ?: statusFilterLabel(estatus)



    // --- ESTADOS PARA LOS SELECTORES DE FECHA ---
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Opciones de filtrado", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Rango de Fechas", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
//                    OutlinedTextField(
//                        value = fechaInicio,
//                        onValueChange = { fechaInicio = it },
//                        label = { Text("Desde") },
//                        modifier = Modifier.weight(1f)
//                    )
//                    OutlinedTextField(
//                        value = fechaFin,
//                        onValueChange = { fechaFin = it },
//                        label = { Text("Hasta") },
//                        modifier = Modifier.weight(1f)
//

                    // --- SELECTOR DE FECHA FIN (CORREGIDO) ---
                    OutlinedTextField(
                        value = fechaInicio,
                        onValueChange = { /* Solo lectura */ },
                        readOnly = true,
                        label = { Text("Desde") },
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            IconButton(onClick = { showStartDatePicker = true }) { // <--- CLIC AQUI
                                Icon(Icons.Default.CalendarToday, contentDescription = "Seleccionar fecha de inicio")
                            }
                        }
                    )

                    // --- SELECTOR DE FECHA FIN (CORREGIDO) ---
                    OutlinedTextField(
                        value = fechaFin,
                        onValueChange = { /* Solo lectura */ },
                        readOnly = true, // Es de solo lectura
                        label = { Text("Hasta") },
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            IconButton(onClick = { showEndDatePicker = true }) { // <--- CLIC AQUI
                                Icon(Icons.Default.CalendarToday, contentDescription = "Seleccionar fecha de fin")
                            }
                        }
                    )
                }

                OutlinedTextField(
                    value = cliente,
                    onValueChange = { cliente = it },
                    label = { Text("Cliente") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = producto,
                    onValueChange = { producto = it },
                    label = { Text("Producto") },
                    modifier = Modifier.fillMaxWidth()
                )
//                OutlinedTextField(
//                    value = estatus,
//                    onValueChange = { estatus = it },
//                    label = { Text("Estatus (PENDIENTE_SYNC, etc.)") },
//                    modifier = Modifier.fillMaxWidth()
//                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        // Mostrar el nombre legible, NO la clave
                        value = selectedStatusName,
                        onValueChange = {}, // No se edita manualmente
                        readOnly = true,
                        label = { Text("Estatus") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        estatusOptions.forEach { (clave, nombre) ->
                            DropdownMenuItem(
                                text = { Text(nombre) },
                                onClick = {
                                    estatus = clave // Actualiza la clave del estatus
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }


        },
        confirmButton = {
            Button(onClick = {
                onApply(
                    PedidoFilters(
                        fechaInicio = fechaInicio.ifEmpty { null },
                        fechaFin = fechaFin.ifEmpty { null },
                        clienteNombre = cliente.ifEmpty { null },
                        estatus = estatus.ifEmpty { null },
                        productoNombre = producto.ifEmpty { null }
                    )
                )
            }) {
                Text("Aplicar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
    if (showStartDatePicker) {
        DatePickerDialogWrapper(
            initialTimestamp = if (fechaInicio.isNotEmpty()) fechaInicio.toTimestamp() else null,
            onDismiss = { showStartDatePicker = false },
            onDateSelected = { timestamp ->
                fechaInicio = timestamp.toDateString()
                showStartDatePicker = false
            }
        )
    }

    // --- DIALOGO DE FECHA DE FIN ---
    if (showEndDatePicker) {
        DatePickerDialogWrapper(
            initialTimestamp = if (fechaFin.isNotEmpty()) fechaFin.toTimestamp() else null,
            onDismiss = { showEndDatePicker = false },
            onDateSelected = { timestamp ->
                fechaFin = timestamp.toDateString()
                showEndDatePicker = false
            }
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialogWrapper(
    initialTimestamp: Long?,
    onDismiss: () -> Unit,
    onDateSelected: (Long) -> Unit
) {
    val dateState = rememberDatePickerState(
        // Si hay una fecha inicial, úsala, sino usa el día actual.
        initialSelectedDateMillis = initialTimestamp ?: System.currentTimeMillis(),
        initialDisplayMode = DisplayMode.Picker
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    // Solo si hay una fecha seleccionada
                    dateState.selectedDateMillis?.let(onDateSelected)
                },
                enabled = dateState.selectedDateMillis != null
            ) {
                Text("Aceptar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    ) {
        DatePicker(state = dateState)
    }
}

fun Long.toDateString(): String {
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    // Forzar UTC para evitar desfases de medianoche
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(java.util.Date(this))
}

fun String.toTimestamp(): Long {
    return try {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        // Forzar UTC para evitar desfases de medianoche
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        formatter.parse(this)?.time ?: System.currentTimeMillis()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}

// ------------------------------------------------------------------------------------------
// VISTA: LISTA DE PEDIDOS
// ------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PedidosList(
    pedidos: List<GetPedidosFiltrados>,
    onPedidoClick: (GetPedidosFiltrados) -> Unit,
) {
    if (pedidos.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("No se encontraron pedidos.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pedidos) { pedido ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPedidoClick(pedido) },
                    shape = MaterialTheme.shapes.medium
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                "Folio ${pedido.folio} "+if(pedido.uuid_local==null && pedido.synced_at!="SYNCHRONIZED"){""}else{"ID Server ${pedido.uuid_server}"},
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        supportingContent = {
                            Text(
                                pedido.nombreCliente ?: "Sin cliente",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "$${String.format("%.2f", pedido.total_erp?.toDoubleOrNull() ?: pedido.total_solicitado ?: 0.0)}",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    statusFilterLabel(pedido.synced_at),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                pedido.estado_erp?.let {
                                    Text("ERP: $it", style = MaterialTheme.typography.labelSmall)
                                }
                                Text(
                                    pedido.fecha_creacion ?: "N/A",
                                    style = MaterialTheme.typography.labelSmall
                                )

                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// VISTA: DETALLE DEL PEDIDO
// ------------------------------------------------------------------------------------------

@Composable
fun PedidoDetail(
    pedido: GetPedidosFiltrados,
    detalle: List<GetDetalleConProductoPorPedidoId>,
    onPrintTicket: (Long) -> Unit,
    onRetry: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Resumen del Pedido",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Cliente: ${pedido.nombreCliente}")
                    Text("Folio: ${pedido.folio}")
                    Text("Fecha: ${pedido.fecha_creacion}")
                    Text("Estatus: ${statusFilterLabel(pedido.synced_at)}")
                    Text("Estado ERP: ${pedido.estado_erp ?: "Pendiente de consultar"}")
                    Text("Documento: ${pedido.tipo_documento} | Tipo de venta: ${pedido.tipo_venta ?: "Revisar captura antigua"}")
                    pedido.total_erp?.let { Text("Total definitivo ERP: $$it") }
                    pedido.serie_erp?.let { Text("Serie ERP: $it | ID ERP: ${pedido.uuid_server}") }
                    pedido.fecha_recepcion?.let { Text("Recepcion ERP: $it | Version: ${pedido.version_erp.orEmpty()}") }
                    diferenciasComerciales(pedido.resultado_comercial).forEach { Text(it) }
                    if (pedido.synced_at in listOf("PENDIENTE_REVISION", "ERROR_REINTENTABLE", "ERROR_NO_REINTENTABLE")) {
                        Text("Revisar no acepta diferencias. Un reintento conserva toda la captura y consulta nuevamente al ERP.")
                        OutlinedButton(onClick = onRetry) { Text("Preparar reintento posterior") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Total local de referencia: $${pedido.total_solicitado}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            // IMPORTANTE: Asumo que tu objeto 'GetPedidosFiltrados'
                            // tiene la ID del pedido. Si no se llama 'pedido_id',
                            // cámbialo por el nombre correcto (ej: pedido.id)
                            onPrintTicket(pedido.id_pedido)
                        },
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        // Puedes usar el icono que prefieras
                        Icon(Icons.Default.Print, contentDescription = "Imprimir")
                        Spacer(Modifier.width(8.dp))
                        Text("Reimprimir Ticket")
                    }


                }
            }
        }

        if (detalle.isNotEmpty()) {
            item {
                Text(
                    "Productos (${detalle.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(detalle) { linea ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    ListItem(
                        headlineContent = { Text(linea.nombreProducto ?: "Producto Desconocido") },
                        supportingContent = {
                            Text("Cant: ${linea.cantidad_solicitada} | Precio: $${linea.precio_unitario}")
                        },
                        trailingContent = {
                            Text(
                                "$${linea.total_linea}",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                }
            }
        } else {
            item {
                Text("No hay productos registrados en este pedido.")
            }
        }
    }
}
