package com.cyssoftware.effyflow.pedido.nuevoPedido.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyssoftware.effyflow.db.Clientes
import com.cyssoftware.effyflow.db.Series_folios
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.NuevoPedidoState
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.NuevoPedidoViewModel
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.PedidoStep
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.TipoPedido
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.cyssoftware.effyflow.db.SearchProductosConDetalleStock
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.PedidoUIEvent
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.ResumenGrupo
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.TipoDocumento


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuevoPedidoScreen(
    viewModel: NuevoPedidoViewModel,
    onSave: () -> Unit,
    onPrintTicket: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var showCancelDialog by remember { mutableStateOf(false) }
    // ðŸŽ¨ Fondo con gradiente (consistente con Home y Sync)

    var showProductoExistenteDialog by remember { mutableStateOf(false) }
    var productoAlertName by remember { mutableStateOf("") }

    LaunchedEffect(state.eventoUI) {
        when (val event = state.eventoUI) {
            is PedidoUIEvent.AlertaProductoExistente -> {
                productoAlertName = event.nombreProducto
                showProductoExistenteDialog = true // Muestra el diálogo
                // No consumir evento aquí, se consume cuando el usuario presiona "Aceptar"
            }

            is PedidoUIEvent.ProductoAgregadoExitosamente -> {
                // Si quieres mostrar un Toast de éxito (opcional)
                viewModel.consumirEventoUI() // Limpia el evento inmediatamente
            }

            null -> Unit // No hay evento que procesar
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(0.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {

        Column(
            modifier = Modifier.fillMaxSize().fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ðŸ§­ Barra superior con botón de volver
            TopAppBar(
                title = {
                    Text(
                        "Nuevos Pedidos",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.currentStep == PedidoStep.SERIE_FOLIO || state.currentStep == PedidoStep.EXITO) {
                            onBack()
                        } else {
                            viewModel.retrocederPaso()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Volver",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )


            Card(
                modifier = Modifier
                    //.fillMaxSize()
                    .fillMaxWidth()
                    .widthIn(max = 1280.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxHeight(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(8.dp)
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = when (state.currentStep) {
                                        PedidoStep.SERIE_FOLIO -> "Paso 1: Serie y Folio"
                                        PedidoStep.CLIENTE -> "Paso 2: Cliente"
                                        PedidoStep.PRODUCTOS -> "Paso 3: Productos"
                                        PedidoStep.RESUMEN -> "Paso 4: Resumen del Pedido"
                                        PedidoStep.EXITO -> "Pedido Guardado"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            actions = {
                                // âœ… 3. BOTÓN CANCELAR (Solo visible si no ha terminado)
                                if (state.currentStep != PedidoStep.EXITO) {
                                    TextButton(onClick = { showCancelDialog = true }) {
                                        Text(
                                            text = "Cancelar pedido",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    },
                    containerColor = Color.Transparent
                ) { padding ->

                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {

                        /* if (state.currentStep != PedidoStep.SERIE_FOLIO && state.currentStep != PedidoStep.EXITO) {
                            OutlinedButton(
                                onClick = { viewModel.retrocederPaso() },
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Atrás")
                            }
                        }*/

                        if (state.error != null) {
                            Text(
                                text = state.error!!,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        if (state.isLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }


                        // --- CONTENIDO PRINCIPAL ---
                        Box(modifier = Modifier.weight(1f)) {
                            when (state.currentStep) {

                                PedidoStep.SERIE_FOLIO -> Step1_SelectSerie(
                                    series = state.seriesDisponibles,
                                    error = state.error,
                                    onSelect = { serie, folioManual ->
                                        viewModel.onSerieSeleccionada(serie, folioManual)
                                    })


//                                PedidoStep.CLIENTE -> Step2_SelectCliente(
//                                    clientes = state.clientesEncontrados,
//                                    onSearch = { viewModel.searchCliente(it) },
//                                    // La nueva firma de onSelect debe ser: (Cliente, TipoPedido, Long)
//                                    onSelect = { cliente, tipo, plazo ->
//                                        viewModel.onClienteSeleccionado(cliente, tipo, plazo)
//                                    },
//                                    serieNombre = state.serieSeleccionada?.codigo_serie
//                                        ?: "Sin Serie",
//                                    folioNombre = state.folioAsignado.toString()
//
//                                )
                                PedidoStep.CLIENTE -> {
                                    Step2_SelectCliente(
                                        clientes = state.clientesEncontrados,
                                        // PASA ESTOS NUEVOS PARÁMETROS:
                                        direcciones = state.direccionesDisponibles,
                                        direccionSeleccionada = state.direccionSeleccionada,
                                        onSearch = { viewModel.searchCliente(it) },
                                        onSelect = { cliente, tipo, plazo ->
                                            // Esta función ahora solo carga datos, no salta de paso
                                            viewModel.onClienteSeleccionado(cliente, tipo, plazo)
                                        },
                                        // Acción para cuando el usuario toca una dirección
                                        onDireccionSelected = { viewModel.seleccionarDireccion(it) },
                                        // Acción para el botón final de "Continuar"
                                        onConfirmarDireccion = { viewModel.confirmarUbicacionYContinuar() },
                                        serieNombre = state.serieSeleccionada?.codigo_serie ?: "",
                                        folioNombre = state.folioAsignado.toString()
                                    )
                                }

                                PedidoStep.PRODUCTOS -> Step3_CaptureProductos(
                                    state = state,
                                    onSearch = { viewModel.searchProducto(it) },
                                    onAdd = { prod, cant ->
                                        viewModel.onAddProducto(
                                            prod,
                                            cant,
                                            true
                                        )
                                    },
                                    onAddOne = { prod, cantidad ->
                                        viewModel.aumentarCantidadProducto(
                                            prod,
                                            cantidad,
                                            false
                                        )
                                    },
                                    onSubtract = { prod -> viewModel.disminuirCantidadProducto(prod) },
                                    viewModel,
                                    serieNombre = state.serieSeleccionada?.codigo_serie
                                        ?: "Sin Serie",
                                    folioNombre = state.folioAsignado.toString(),
                                    clienteNombre = state.clienteSeleccionado?.nombre ?: "",
                                    plazo = state.plazoSeleccionado.toInt(),
                                    direccion= state.direccionSeleccionada
                                )

                                PedidoStep.RESUMEN -> Step4_Resumen(viewModel)

                                PedidoStep.EXITO -> Step5_Exito(
                                    viewModel = viewModel,
                                    onFinish = onSave,
                                    onPrint = onPrintTicket
                                )
                            }
                        }
                        if (showProductoExistenteDialog) {
                            AlertDialog(
                                onDismissRequest = {
                                    showProductoExistenteDialog = false
                                    viewModel.consumirEventoUI() // Importante: Limpiar el evento
                                },
                                title = { Text("Producto ya Agregado") },
                                text = {
                                    Text(
                                        "El producto **'${productoAlertName}'** ya se encontraba en la lista del pedido. " +
                                                "Se ha sumado la cantidad solicitada."
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            showProductoExistenteDialog = false
                                            viewModel.consumirEventoUI() // Importante: Limpiar el evento
                                        }
                                    ) {
                                        Text("Aceptar")
                                    }
                                }
                            )
                        }
                        // --- BOTONES DE NAVEGACIÓN ---
                        when (state.currentStep) {
                            PedidoStep.PRODUCTOS -> {
                                if (state.lineasPedido.isNotEmpty()) {
                                    MainActionButton(
                                        text = "Revisar Pedido",
                                        icon = Icons.Default.List,
                                        color = MaterialTheme.colorScheme.primary,
                                        enabled = !state.isLoading
                                    ) { viewModel.onRevisarPedido() }
                                }
                            }

                            PedidoStep.RESUMEN -> {
                                MainActionButton(
                                    text = "Confirmar y Guardar Pedido",
                                    icon = Icons.Default.CheckCircle,
                                    color = MaterialTheme.colorScheme.secondary,
                                    enabled = !state.isLoading
                                ) { viewModel.onConfirmarYSGuardar() }
                            }

                            else -> {}
                        }
                    }
                }
            }

            if (showCancelDialog) {
                AlertDialog(
                    onDismissRequest = { showCancelDialog = false },
                    title = { Text("¿Cancelar Pedido?") },
                    text = { Text("Se perderán los datos ingresados y volverás al inicio.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showCancelDialog = false
                                viewModel.reiniciarFlujo() // Limpia el ViewModel
                                onBack() // Sale de la pantalla
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Sí, Cancelar")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCancelDialog = false }) {
                            Text("No, continuar")
                        }
                    }
                )
            }
        }
    }
}

// ðŸ”¹ BOTÓN PRINCIPAL REUTILIZABLE
@Composable
fun MainActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(top = 8.dp),
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

@Composable
fun Step1_SelectSerie(
    series: List<Series_folios>,
    error: String?,
    onSelect: (Series_folios, Long?) -> Unit,
) {
    var selectedSerie by remember { mutableStateOf<Series_folios?>(null) }
    var folioManual by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Seleccione una serie",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (selectedSerie == null) {
                items(series) { serie ->
                    val esConsecutiva = (serie.es_consecutiva ?: 1L) != 0L
                    ListItem(
                        headlineContent = { Text(serie.codigo_serie ?: "Sin codigo") },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    serie.descripcion,
                                    if (esConsecutiva) "Folio automatico" else "Folio manual"
                                ).joinToString(" · ")
                            )
                        },
                        leadingContent = { RadioButton(selected = false, onClick = null) },
                        modifier = Modifier.clickable {
                            selectedSerie = serie
                            folioManual = ""
                        }
                    )
                    HorizontalDivider()
                }
            } else {
                item {
                    val serie = selectedSerie!!
                    val esConsecutiva = (serie.es_consecutiva ?: 1L) != 0L
                    ListItem(
                        headlineContent = { Text("${serie.id_serie} - ${serie.codigo_serie ?: "Sin codigo"}") },
                        supportingContent = { Text(serie.descripcion ?: "") },
                        trailingContent = { Text(if (esConsecutiva) "Automatico" else "Manual") },
                        leadingContent = { RadioButton(selected = true, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    )
                    HorizontalDivider()

                    OutlinedButton(
                        onClick = {
                            selectedSerie = null
                            folioManual = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Cambiar serie")
                    }

                    if (!esConsecutiva) {
                        OutlinedTextField(
                            value = folioManual,
                            onValueChange = { folioManual = it.filter { c -> c.isDigit() } },
                            label = { Text("Folio manual") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    } else {
                        Text(
                            "El folio se asignara automaticamente segun la serie.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    if (error != null) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    Button(
                        onClick = {
                            val folio = if (esConsecutiva) null else folioManual.toLongOrNull()
                            onSelect(serie, folio)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        enabled = esConsecutiva || folioManual.isNotBlank()
                    ) {
                        Text("Continuar")
                    }
                }
            }
        }
    }
}

//fun Step1_SelectSerie(series: List<Series_folios>, onSelect: (Series_folios) -> Unit) {
//    LazyColumn(modifier = Modifier.fillMaxSize()) {
//        items(series) { serie ->
//            ListItem(
//                headlineContent = { Text(serie.codigo_serie ?: "Sin Código") },
//                supportingContent = { Text(serie.descripcion ?: "") },
//                trailingContent = { Text("Tipo: ${serie.tipo_serie}") },
//                modifier = Modifier.clickable { onSelect(serie) }
//            )
//            HorizontalDivider()
//        }
//    }
//}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Step2_SelectCliente(
    clientes: List<Clientes>,
    onSearch: (String) -> Unit,
    direcciones: List<PedidoRepository.DireccionCombo>, // Nueva
    direccionSeleccionada: PedidoRepository.DireccionCombo?, // Nueva
    // La nueva firma de la lambda para enviar Cliente, Tipo y Plazo
    onSelect: (Clientes, TipoPedido, Long) -> Unit,
    onDireccionSelected: (PedidoRepository.DireccionCombo) -> Unit, // Nueva
    onConfirmarDireccion: () -> Unit,
    serieNombre: String,
    folioNombre: String,

    ) {
    var selectedCliente by remember { mutableStateOf<Clientes?>(null) }
    var tipoPedido by remember { mutableStateOf(TipoPedido.NINGUNO) }
    var query by remember { mutableStateOf("") }

    // Estado para el plazo que el usuario ingresa (si aplica)
    var plazoSeleccionadoStr by remember { mutableStateOf("0") }

    // ðŸ”¹ Lógica de inicialización al seleccionar cliente
    LaunchedEffect(selectedCliente) {
        val cliente = selectedCliente
        if (cliente != null) {
            val plazoCliente = cliente.plazo ?: 0
            if (plazoCliente <= 0 || cliente.credito_bloqueado == 1L) {
                // Forzar CONTADO y plazo 0 si no hay plazo de crédito
                tipoPedido = TipoPedido.CONTADO
                plazoSeleccionadoStr = "0"
            } else {
                // Resetear el tipo y poner el plazo máximo del cliente como valor por defecto
                tipoPedido = TipoPedido.CREDITO
                plazoSeleccionadoStr = plazoCliente.toString()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(6.dp)) {
                Text(
                    text = "Información del Pedido",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))

                // ðŸš¨ ESTRUCTURA DE 2 COLUMNAS (50/50) ðŸš¨
                Row(modifier = Modifier.fillMaxWidth()) {

                    // Columna 1: Serie (50% de ancho)
                    Column(modifier = Modifier.weight(0.5f)) {
                        Text(
                            "Serie:",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(serieNombre, style = MaterialTheme.typography.bodyLarge)
                    }

                    // Columna 2: Folio (50% de ancho)
                    Column(modifier = Modifier.weight(0.5f)) {
                        Text(
                            "Folio:",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(folioNombre, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        if (selectedCliente == null) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onSearch(it)
                },
                label = { Text("Buscar cliente por nombre o RFC") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                enabled = (selectedCliente == null), // Deshabilitar search si ya seleccionó
                trailingIcon = {
                    // Muestra el icono de borrado solo si hay texto en la caja
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                query = ""      // 1. Limpia la variable de estado local 'query'
                                onSearch("")    // 2. Ejecuta la búsqueda con cadena vacía para limpiar la lista de resultados
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close, // Ícono de cierre/borrado
                                contentDescription = "Limpiar búsqueda"
                            )
                        }
                    }
                }
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {

            // ------------------------------------------------
            // 1. Etapa de SELECCIÓN DE CLIENTE
            // ------------------------------------------------
            if (selectedCliente == null) {
                items(clientes) { cliente ->
                    ListItem(
                        headlineContent = { Text(cliente.nombre) },
                        supportingContent = { Text(cliente.rfc ?: "Sin RFC") },
                        trailingContent = { Text("Plazo: ${cliente.plazo ?: 0} días") },
                        leadingContent = { RadioButton(selected = false, onClick = null) },
                        modifier = Modifier.clickable {
                            selectedCliente = cliente
                            onSelect(cliente, TipoPedido.NINGUNO, 0)
                        }
                    )
                    HorizontalDivider()
                }
            } else {

                // ------------------------------------------------
                // 2. Etapa de OPCIONES DE PAGO
                // ------------------------------------------------


                item {
                    val cliente = selectedCliente!!
                    val plazoMaximo = cliente.plazo ?: 0
                    val puedeCredito = plazoMaximo > 0 && cliente.credito_bloqueado != 1L
                    val condicionTexto = if (puedeCredito) "Condición: Crédito · $plazoMaximo días" else "Condición: Contado"

                    // ðŸ”¹ Cliente Seleccionado (Fijo en la parte superior)
                    ListItem(
                        headlineContent = { Text(cliente.nombre) },
                        supportingContent = { Text(cliente.rfc ?: "Sin RFC") },
                        trailingContent = { Text("Plazo Máx: ${plazoMaximo} días") },
                        leadingContent = { RadioButton(selected = true, onClick = null) },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    HorizontalDivider()

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            condicionTexto,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ðŸ”¹ Botón para quitar selección
                    OutlinedButton(
                        onClick = {
                            selectedCliente = null
                            tipoPedido = TipoPedido.NINGUNO
                            plazoSeleccionadoStr = "0"
                            query = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text("Cambiar Cliente")
                    }

                    // --- Selección de tipo de pedido (Contado / Crédito) ---
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "1. Seleccione el tipo de pago",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp, start = 16.dp)
                    )

                    val options = mutableListOf("Contado")
                    if (puedeCredito) {
                        options.add("Crédito (${plazoMaximo} días)")
                    }

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        options.forEach { label ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = options.indexOf(label),
                                    count = options.size
                                ),
                                onClick = {
                                    tipoPedido = if (label == "Contado") {
                                        plazoSeleccionadoStr = "0" // Forzar plazo a 0
                                        TipoPedido.CONTADO
                                    } else {
                                        plazoSeleccionadoStr =
                                            plazoMaximo.toString() // Default al máximo
                                        TipoPedido.CREDITO
                                    }
                                },
                                selected = (tipoPedido == TipoPedido.CONTADO && label == "Contado") ||
                                        (tipoPedido == TipoPedido.CREDITO && label.startsWith("Crédito"))
                            ) {
                                Text(if (label.startsWith("Crédito")) "Crédito" else label)
                            }
                        }
                    }

                    // ------------------------------------------------
                    // 3. Etapa de DEFINICIÃ“N DE PLAZO (Solo si es Crédito)
                    // ------------------------------------------------
                    if (tipoPedido == TipoPedido.CREDITO && puedeCredito) {
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = plazoSeleccionadoStr,
                            onValueChange = { _ ->
                                // Filtrar solo dÃ­gitos
                                val filteredValue = plazoMaximo.toString()
                                val newPlazo = filteredValue.toLongOrNull() ?: 0L

                                // Limitar al plazo máximo del cliente
                                plazoSeleccionadoStr = if (newPlazo <= plazoMaximo) {
                                    filteredValue
                                } else {
                                    plazoMaximo.toString()
                                }
                            },
                            label = { Text("Días de plazo a aplicar (Máx: $plazoMaximo)") },
                            enabled = false,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        )
                    }

                    Text(
                        "2. Seleccione lugar de entrega",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp, start = 16.dp)
                    )

                    direcciones.forEach { direccion ->
                        val selected = direccion.id == direccionSeleccionada?.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onDireccionSelected(direccion) },
                            border = if (selected) BorderStroke(
                                2.dp,
                                MaterialTheme.colorScheme.primary
                            ) else null
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { onDireccionSelected(direccion) }
                                )
                                Column {
                                    Text(direccion.alias, fontWeight = FontWeight.Bold)
                                    Text(
                                        direccion.direcccion,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    // --- Botón continuar ---
                    val plazoFinal = plazoSeleccionadoStr.toLongOrNull() ?: 0L

                    if (tipoPedido != TipoPedido.NINGUNO) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                // Llama al onSelect con el cliente, el tipo de pedido y el plazo final (0 o >0)
                                onSelect(cliente, tipoPedido, plazoFinal)
                                onConfirmarDireccion()
                            },
                            // Evitar continuar si se eligiÃ³ crédito y el plazo es 0 (no tiene sentido)
                            enabled = if (tipoPedido == TipoPedido.CREDITO) plazoFinal > 0 else true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(horizontal = 16.dp)
                        ) {
                            Text("Continuar a Productos")
                        }
                    }
                }


            }
        }
    }
}

@Suppress("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Step3_CaptureProductos(
    state: NuevoPedidoState,
    onSearch: (String) -> Unit,
    onAdd: (SearchProductosConDetalleStock, Double) -> Unit,
    onAddOne: (Long, Double) -> Unit,
    onSubtract: (Long) -> Unit,
    viewModel: NuevoPedidoViewModel,
    serieNombre: String,
    folioNombre: String,
    clienteNombre: String,
    plazo: Int,
    direccion: PedidoRepository.DireccionCombo?
) {
    // 1. Estado de la consulta de búsqueda
    var query by remember { mutableStateOf("") }

    // 2. Control del diálogo de cantidad (reutilizado por ambas listas)
    var productoSeleccionado by remember { mutableStateOf<SearchProductosConDetalleStock?>(null) }
    var cantidad by remember { mutableStateOf("1") }

    // 3. Estado para controlar la visibilidad del modal de sustitutos
    var showSubstitutesModal by remember { mutableStateOf(false) }

    // â—ï¸ NUEVO: Estado para controlar la visibilidad del modal Top 20
    var showTop20Modal by remember { mutableStateOf(false) }

    // 4. Efecto que se dispara cuando la lista de sustitutos cambia
    LaunchedEffect(state.productosSustitutos) {
        if (state.productosSustitutos.isNotEmpty()) {
            showSubstitutesModal = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(6.dp)) {
                Text(
                    text = "Información del Pedido",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))

                // ðŸš¨ ESTRUCTURA DE 2 COLUMNAS (50/50) ðŸš¨
                Row(modifier = Modifier.fillMaxWidth()) {

                    // Columna 1: Serie (50% de ancho)
                    Column(modifier = Modifier.weight(0.5f)) {
                        Text(
                            "Serie:",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(serieNombre, style = MaterialTheme.typography.bodyLarge)
                    }

                    // Columna 2: Folio (50% de ancho)
                    Column(modifier = Modifier.weight(0.5f)) {
                        Text(
                            "Folio:",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(folioNombre, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {

                    // Columna 1: Serie (50% de ancho)
                    Column(modifier = Modifier.weight(0.5f)) {
                        Text(
                            "Cliente:",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(clienteNombre, style = MaterialTheme.typography.bodyLarge)
                    }
                    Column(modifier = Modifier.weight(0.5f)) {
                        Text(
                            "Plazo:",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            if (plazo == 0) "Contado" else "Crédito ($plazo)",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                if(direccion!=null)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(0.3f)) {
                        Text(
                            "Dirección:",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "${direccion.alias} ${direccion.calle} ${direccion.cp} ",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }


            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val focusRequester = remember { FocusRequester() }

            // 2. Observar si la query se limpia para volver a enfocar
            LaunchedEffect(state.searchQuery) {
                if (state.searchQuery.isEmpty()) {
                    focusRequester.requestFocus()
                }
            }

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearch,
                label = { Text("Buscar producto por nombre o código") },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),// ðŸ”¹ Esto hace que tome todo el espacio disponible
                trailingIcon = {
                    // Muestra el icono de borrado solo si hay texto en la caja
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onSearch("") // â¬…ï¸ Llama a la función de búsqueda para limpiar el texto
                                focusRequester.requestFocus() // Opcional: Mantener el foco en el campo
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close, // Ícono de cierre/borrado
                                contentDescription = "Limpiar búsqueda"
                            )
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {

                    showTop20Modal = !showTop20Modal
                    if (showTop20Modal)
                        onSearch("Top Productos")
                    else
                        onSearch("")
                }
            ) {
                Icon(
                    imageVector =
                        Icons.Default.Star,
                    contentDescription = "Top 20",
                    tint = if (state.searchQuery.equals("Top Productos")) {
                        MaterialTheme.colorScheme.secondary // O Color.Red, Color.Green, etc. para indicar que está ACTIVO
                    } else {
                        MaterialTheme.colorScheme.primary // Color normal cuando está inactivo
                    }
                )
            }
        }


        // ðŸ” MODIFICADO: El título ahora solo aplica a la búsqueda

        if (state.searchQuery.isNotEmpty()) {
            val tituloLista = if (state.searchQuery == "Top Productos")
                "Productos comprados Cliente" // Ya no muestra "más comprados" aquí
            else
                "Productos encontrados"

            Text(
                text = tituloLista,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        // --- Lista de Búsqueda (Principal) ---
        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
            items(state.productosEncontrados) { producto ->
                ListItem(
                    headlineContent = { Text(producto.nombre ?: "") },
                    supportingContent = {
                        Column {
                            // Fila de Marca y Unidad
                            Text(
                                text = "Código: ${producto.codigo ?: "N/A"} | Marca: ${producto.marcaNombre ?: "N/A"} | Unidad: ${producto.unidadNombre ?: "N/A"}",
                                style = MaterialTheme.typography.bodySmall
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Fila de Detalles Financieros y Stock
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // PRECIO (Usando precio_unitario - Ajusta si el nombre del campo es diferente)
                                DetalleEnLinea(
                                    label = "Precio",
                                    value = "$${
                                        String.format(
                                            "%.2f",
                                            producto.precioUnitario ?: 0.0
                                        )
                                    }"
                                )

                                // IMPUESTO (Usando impuesto_nombre - Ajusta si el nombre del campo es diferente)
                                DetalleEnLinea(
                                    label = "Impuesto",
                                    value = producto.impuestosNombres ?: "IVA"
                                )

                                // DESCUENTO (Usando descuento_max)
                                DetalleEnLinea(
                                    label = "Desc. %",
                                    value = String.format(
                                        "%.0f%%",
                                        producto.descuento_max ?: 0.0
                                    )
                                )

                                // EXISTENCIA (Usando existencia_total)
                                DetalleEnLinea(
                                    label = "Stock",
                                    value = String.format(
                                        "%.0f",
                                        producto.existenciaTotal ?: 0.0
                                    ),
                                    color = if ((producto.existenciaTotal
                                            ?: 0.0) > 0
                                    ) Color(
                                        0xFF00C853
                                    ) else MaterialTheme.colorScheme.error // Verde o Rojo
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                if (producto.tipo == "K") {
                                    OutlinedButton(
                                        onClick = { viewModel.consultarComponentesKit(producto.id_producto) },
                                        modifier = Modifier.padding(top = 4.dp),
                                        contentPadding = PaddingValues(
                                            horizontal = 8.dp,
                                            vertical = 0.dp
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Ver componentes", fontSize = 12.sp)
                                    }
                                }
                            }

                        }
                    },
                    trailingContent = {
                        IconButton(
                            onClick = {
                                // ðŸ” REUTILIZA el diálogo de cantidad
                                productoSeleccionado = producto
                                cantidad = "1"

                                val esTruper = producto.marcaNombre?.contains(
                                    "TRUPER",
                                    ignoreCase = true
                                ) == true
                                val innerValue = producto.inner_pack ?: 1

                                cantidad = if (esTruper && innerValue > 0) {
                                    innerValue.toInt().toString()
                                } else {
                                    "1"
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.ShoppingCart,
                                contentDescription = "Agregar"
                            )
                        }
                    }
                )
                HorizontalDivider()
            }
        }

        // --- Mostrar Error de Producto (Sin Stock / Descontinuado) ---
        if (state.productoError != null) {
            Text(
                text = "Aviso: ${state.productoError}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Productos en el pedido:", style = MaterialTheme.typography.titleMedium)


        // --- Controles de Ordenamiento ---
        var sortKey by remember { mutableStateOf("Nombre") }
        val sortOptions = listOf("Nombre", "Categoria", "Marca", "Unidad")

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            sortOptions.forEach { label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = sortOptions.indexOf(label),
                        count = sortOptions.size
                    ),
                    onClick = { sortKey = label },
                    selected = (label == sortKey)
                ) {
                    Text(label)
                }
            }
        }

        // --- Lógica de Ordenamiento ---
        val sortedLineas = remember(state.lineasPedido, sortKey) {
            when (sortKey) {
                "Nombre" -> state.lineasPedido.sortedBy { it.producto.nombre }
                "Código" -> state.lineasPedido.sortedBy { it.producto.codigo }
                "Cantidad" -> state.lineasPedido.sortedByDescending { it.cantidad }
                "Marca" -> state.lineasPedido.sortedBy { it.producto.marcaNombre }
                "Unidad" -> state.lineasPedido.sortedBy { it.producto.unidadNombre }
                "Categoria" -> state.lineasPedido.sortedBy { it.producto.categoriaNombre }
                else -> state.lineasPedido
            }
        }

        // --- Lista de Productos en el pedido (Detallada y Ordenada) ---
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sortedLineas) { linea ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Columna de Información Detallada
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = linea.producto.nombre ?: "N/A", // Descripción
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Código: ${linea.producto.codigo ?: "S/C"}", // Código/Clave
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "Marca: ${linea.producto.marcaNombre ?: "N/A"}", // Marca
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Unidad: ${linea.producto.unidadNombre ?: "N/A"}", // Unidad de medida
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${linea.producto.categoriaNombre ?: "N/A"}", // Unidad de medida
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Precio: $${
                                        String.format(
                                            "%.2f",
                                            linea.precio
                                        )
                                    }", // Precio
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Desc: ${
                                        String.format(
                                            "%.1f%%",
                                            linea.descuento
                                        )
                                    }", // Descuento Máximo
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Text(
                                    text = "Total: $${
                                        String.format(
                                            "%.2f",
                                            linea.total
                                        )
                                    }", // Precio
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )

                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (linea.producto.tipo == "K") {
                                    OutlinedButton(
                                        onClick = { viewModel.consultarComponentesKit(linea.producto.id_producto) },
                                        modifier = Modifier.padding(top = 4.dp),
                                        contentPadding = PaddingValues(
                                            horizontal = 8.dp,
                                            vertical = 0.dp
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Ver componentes", fontSize = 12.sp)
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (linea.producto.marcaNombre?.lowercase() == "truper") {
                                    Text(
                                        text = "Inner: ${
                                            String.format(
                                                "%.0f",
                                                linea.producto.inner_pack?.toDouble()
                                            )
                                        }", // Precio
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Master: ${
                                            String.format(
                                                "%.0f",
                                                linea.producto.master_pack?.toDouble()
                                            )
                                        }", // Precio
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // Controles de cantidad
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = { onSubtract(linea.producto.id_producto) }
                            ) {
                                Icon(
                                    Icons.Default.Remove,
                                    contentDescription = "Disminuir"
                                )
                            }
                            Text(
                                text = linea.cantidad.toInt().toString(), // Cantidad
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = {
                                    val esTruper =
                                        linea.producto.marcaNombre?.lowercase() == "truper"

                                    // 2. Convertir explícitamente a Double y manejar nulos
                                    val cantidadASumar: Double = if (esTruper) {
                                        val inner = linea.producto.inner_pack?.toDouble() ?: 1.0
                                        if (inner > 0) inner else 1.0
                                    } else {
                                        1.0
                                    }

                                    // 3. Llamar a la función con el tipo de dato correcto
                                    onAddOne(linea.producto.id_producto, cantidadASumar)

                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Aumentar")
                            }
                        }
                    }
                }

            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh) // Cambia el fondo para contraste
                        .padding(16.dp)
                ) {

                    // 1. MOSTRAR SUBTOTAL BRUTO
                    RowTotal(
                        label = "Subtotal (Bruto):",
                        amount = state.subtotalBruto.toCurrency(),
                        style = MaterialTheme.typography.bodyLarge
                    )

                    // 2. MOSTRAR DESCUENTO
                    RowTotal(
                        label = "Descuento:",
                        amount = "- ${state.totalDescuento.toCurrency()}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Red // Color para indicar resta
                    )

                    // 3. MOSTRAR IMPUESTOS
                    RowTotal(
                        label = "Total Impuestos:",
                        amount = state.totalImpuestos.toCurrency(),
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Divider(modifier = Modifier.padding(vertical = 4.dp))

                    // 4. MOSTRAR TOTAL NETO
                    RowTotal(
                        label = "Total Final:",
                        amount = state.totalPedido.toCurrency(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                }
            }
        }
    }
    // 1. Agrega este estado al principio de tu Step3_CaptureProductos
    var errorMensaje by remember { mutableStateOf<String?>(null) }

    if (productoSeleccionado != null) {
        AlertDialog(
            onDismissRequest = {
                productoSeleccionado = null
                errorMensaje = null // Limpiar error al cerrar
            },
            title = { Text("Agregar producto") },
            text = {
                Column {
                    Text(
                        productoSeleccionado?.nombre ?: "",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text("Marca: ${productoSeleccionado?.marcaNombre ?: ""}")
                    Text("Existencia: ${productoSeleccionado?.existenciaTotal}")

                    if (productoSeleccionado?.marcaNombre?.lowercase() == "truper") {
                        val inner = productoSeleccionado?.inner_pack?.toDouble() ?: 1.0
                        Text(
                            "Mínimo de venta (Inner): $inner",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = cantidad,
                        onValueChange = { input ->
                            cantidad = input.filter { it.isDigit() }
                            errorMensaje = null // Limpiar error mientras escribe
                        },
                        label = { Text("Cantidad") },
                        isError = errorMensaje != null, // Pone el cuadro en rojo si hay error
                        supportingText = {
                            if (errorMensaje != null) {
                                Text(errorMensaje!!, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cant = cantidad.toDoubleOrNull() ?: 0.0
                        val inner = productoSeleccionado?.inner_pack?.toDouble() ?: 0.0
                        val esTruper = productoSeleccionado?.marcaNombre?.lowercase() == "truper"

                        // VALIDACIÃ“N
                        if (cant <= 0) {
                            errorMensaje = "Ingrese una cantidad válida"
                        } else if (esTruper && inner > 0 && (cant % inner) != 0.0) {
                            // Si es truper e inner > 0, validar que sea múltiplo
                            errorMensaje = "La cantidad debe ser múltiplo de $inner"
                        } else {
                            // Si pasa las validaciones, agregar
                            onAdd(productoSeleccionado!!, cant)
                            productoSeleccionado = null
                            errorMensaje = null
                            cantidad = "1"
                        }
                    }
                ) {
                    Text("Agregar")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    productoSeleccionado = null
                    errorMensaje = null
                }) {
                    Text("Cancelar")
                }
            }
        )
    }
    // --- Diálogo para elegir cantidad (Reutilizado) ---
    /* if (productoSeleccionado != null) {
         AlertDialog(
             onDismissRequest = { productoSeleccionado = null
                 errorMensaje = null},
             title = { Text("Agregar producto") },
             text = {
                 Column {
                     Text(productoSeleccionado?.nombre ?: "")
                     Text(productoSeleccionado?.marcaNombre ?: "")
                     Text("Existencia: "+productoSeleccionado?.existenciaTotal.toString())
                     if(productoSeleccionado!!.marcaNombre?.lowercase()=="truper"){
                         Text("Inner: "+productoSeleccionado?.inner_pack.toString())
                         Text(productoSeleccionado?.master_pack.toString())
                     }
                     OutlinedTextField(
                         value = cantidad,
                         onValueChange = { input ->
                             val filtrado = input.filter { it.isDigit() }
                             cantidad = filtrado
                         },
                         label = { Text("Cantidad") },
                         singleLine = true,
                         modifier = Modifier.fillMaxWidth(),
                         keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)

                     )
                 }
             },
             confirmButton = {
                 TextButton(
                     onClick = {
                         val cant = cantidad.toDoubleOrNull()
                         if (cant != null && cant > 0) {
                             onAdd(productoSeleccionado!!, cant)
                             productoSeleccionado = null
                         }
                     }
                 ) {
                     Text("Agregar")
                 }
             },
             dismissButton = {
                 TextButton(onClick = { productoSeleccionado = null }) {
                     Text("Cancelar")
                 }
             }
         )
     }*/


    state.componentesKitSeleccionado?.let { componentes ->
        AlertDialog(
            onDismissRequest = { viewModel.cerrarDetalleKit() },
            title = {
                Column {
                    Text(
                        "Desglose de Componentes",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Detalles técnicos y existencias",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Box(modifier = Modifier.heightIn(max = 450.dp)) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(componentes) { item ->
                            // item.producto es de tipo GetProductoDetalladoStockById
                            val producto = item.producto

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.5f
                                    )
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    // Fila Superior: Nombre y Cantidad requerida
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            text = producto.nombre ?: "Sin nombre",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Text(
                                                text = "Requerido: ${item.cantidad.toInt()}",
                                                modifier = Modifier.padding(
                                                    horizontal = 8.dp,
                                                    vertical = 2.dp
                                                ),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }

                                    Text(
                                        text = "Código: ${producto.codigo ?: "N/A"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        thickness = 0.5.dp
                                    )

                                    // Información técnica y existencias
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        // 1. EXISTENCIA REAL (Dato crítico del SQL enriquecido)
                                        val stockDisponible = producto.existenciaTotal ?: 0.0
                                        DatoComponente(
                                            label = "Existencia",
                                            value = stockDisponible.toInt().toString(),
                                            // Rojo si no hay suficiente para el kit, verde si sí
                                            color = if (stockDisponible >= item.cantidad)
                                                Color(0xFF2E7D32) else Color.Red
                                        )

                                        // 2. MARCA
                                        DatoComponente(
                                            label = "Marca",
                                            value = producto.marcaNombre ?: "N/A"
                                        )

                                        // 3. PESO (Requerimiento 4.1)
                                        DatoComponente(
                                            label = "Tipo",
                                            value = producto.tipo ?: "N/A"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.cerrarDetalleKit() }
                ) {
                    Text("Cerrar", fontWeight = FontWeight.Bold)
                }
            }
        )
    }


    // --- Modal de Productos Sustitutos (Sin cambios funcionales) ---
    // (No se puede agregar directo desde aquí por limitaciones del ViewModel/Repo)
    if (showSubstitutesModal) {
        AlertDialog(
            onDismissRequest = { showSubstitutesModal = false },
            title = { Text("Producto Sin Existencia") },
            text = {
                Column {
                    Text(
                        state.productoError ?: "El producto no tiene existencias.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Puede seleccionar un sustituto de la lista para buscarlo:",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // Lista de los sustitutos
                    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                        items(state.productosSustitutos) { productoSustituto ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        productoSustituto.nombre ?: ""
                                    )
                                },
                                supportingContent = { Text("Código: ${productoSustituto.codigo ?: "S/C"}") },
                                modifier = Modifier
                                    .clickable {
                                        // 1. Pone el código del sustituto en la barra de búsqueda
                                        query = productoSustituto.codigo ?: ""
                                        // 2. Llama a onSearch para refrescar la lista principal
                                        onSearch(query)
                                        // 3. Cierra este modal
                                        showSubstitutesModal = false
                                    }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubstitutesModal = false }) {
                    Text("Cerrar")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Step4_Resumen(
    viewModel: NuevoPedidoViewModel,
) {
    val state by viewModel.state.collectAsState()

    // Estado local para el selector de vista
    var vistaSeleccionada by remember { mutableStateOf("Productos") }
    val opciones = listOf("Productos", "Marcas", "Categorías")

    // Obtenemos los análisis agrupados del ViewModel
    val analisisMarca = viewModel.getResumenPorMarca()
    val analisisCat = viewModel.getResumenPorCategoria()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {

        // 1. DATOS GENERALES DEL PEDIDO
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Información del Pedido",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Serie/Folio: ${state.serieSeleccionada?.codigo_serie ?: "N/A"}-${state.folioAsignado ?: "Pendiente"}")
                    Text("Cliente: ${state.clienteSeleccionado?.nombre ?: "No seleccionado"}")
                    Text("Condición: ${if (state.plazoSeleccionado.toInt() == 0) "Contado" else "Crédito"}")
                    if (state.plazoSeleccionado > 0) {
                        Text("Plazo: ${state.plazoSeleccionado.toInt()} días")
                    }
                    val direccion=state.direccionSeleccionada
                    if(direccion!=null)
                    Text(
                        text = "Dirección: ${direccion.alias}. ${direccion.direcccion} ",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 620.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.irAPaso(PedidoStep.SERIE_FOLIO) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("Editar serie") }
                        OutlinedButton(
                            onClick = { viewModel.irAPaso(PedidoStep.CLIENTE) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("Editar cliente") }
                        Button(
                            onClick = { viewModel.irAPaso(PedidoStep.PRODUCTOS) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("Volver a editar") }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.irAPaso(PedidoStep.SERIE_FOLIO) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) { Text("Editar serie") }
                        OutlinedButton(
                            onClick = { viewModel.irAPaso(PedidoStep.CLIENTE) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) { Text("Editar cliente") }
                        Button(
                            onClick = { viewModel.irAPaso(PedidoStep.PRODUCTOS) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) { Text("Volver a editar") }
                    }
                }
            }
        }

        // 2. OBSERVACIONES
        item {
            OutlinedTextField(
                value = state.observacion,
                onValueChange = { viewModel.updateObservacion(it) },
                label = { Text("Observaciones") },
                placeholder = { Text("Instrucciones de entrega, notas, etc...") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                singleLine = false,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
        }

        // 3. TIPO DE DOCUMENTO
        item {
            TipoDocumentoSelection(state = state, viewModel = viewModel)
        }

        // 4. SELECTOR DE VISTA (SEGMENTED BUTTON)
        item {
            Column {
                Text(
                    text = "Visualizar detalle por:",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    opciones.forEachIndexed { index, label ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = opciones.size
                            ),
                            onClick = { vistaSeleccionada = label },
                            selected = vistaSeleccionada == label
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        // 5. CONTENIDO DINÁMICO (PRODUCTOS / MARCAS / CATEGORÍAS)
        when (vistaSeleccionada) {
            "Productos" -> {
                items(state.lineasPedido) { linea ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = 0.5f
                            )
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                linea.producto.nombre?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    "Cant: ${linea.cantidad.toInt()} | P.U: ${linea.producto.precioUnitario?.toCurrency()}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                linea.total.toCurrency(),
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            "Marcas" -> {
                items(analisisMarca) { grupo ->
                    ResumenGrupoCard(grupo)
                }
            }

            "Categorías" -> {
                items(analisisCat) { grupo ->
                    ResumenGrupoCard(grupo)
                }
            }
        }

        // 6. SECCIÓN DE TOTALES FINALES
        item {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(
                        alpha = 0.3f
                    )
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ResumenTotalRow("Subtotal", state.subtotalBruto)
                    ResumenTotalRow("Descuento", state.totalDescuento)
                    ResumenTotalRow("Impuestos", state.totalImpuestos)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            "TOTAL FINAL",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = state.totalPedido.toCurrency(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ResumenGrupoCard(grupo: ResumenGrupo) {
    var expandido by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandido = !expandido }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = grupo.nombre,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${grupo.productos.size} productos | ${
                            String.format(
                                "%.2f",
                                grupo.pesoTotal
                            )
                        } kg", style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    grupo.montoTotal.toCurrency(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Icon(
                    if (expandido) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }

            if (expandido) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .padding(bottom = 8.dp)
                ) {
                    grupo.productos.forEach { det ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${det.cantidad.toInt()}x ${det.productoNombre}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                det.total.toCurrency(),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResumenTotalRow(label: String, monto: Double) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(monto.toCurrency(), style = MaterialTheme.typography.bodyMedium)
    }
}

//@Composable
//fun Step4_Resumen(
//    state: NuevoPedidoState,
//    viewModel: NuevoPedidoViewModel,
//) {
//
//    val state by viewModel.state.collectAsState()
//    var criterioAgrupamiento by remember { mutableStateOf("Marca") }
//
//    val resumen = if (criterioAgrupamiento == "Marca") {
//        viewModel.getResumenPorMarca()
//    } else {
//        viewModel.getResumenPorCategoria()
//    }
//
//// Estado local para el selector de vista
//    var vistaSeleccionada by remember { mutableStateOf("Productos") }
//    val opciones = listOf("Productos", "Marcas", "Categorías")
//
//    // CÃ¡lculos obtenidos del ViewModel (usando las funciones sugeridas anteriormente)
//    val analisisMarca = viewModel.getResumenPorMarca()
//    val analisisCat = viewModel.getResumenPorCategoria()
//
//    LazyColumn(
//        modifier = Modifier
//            .fillMaxSize()
//            .padding(8.dp),
//        verticalArrangement = Arrangement.spacedBy(8.dp)
//    ) {
//
//        // Datos del pedido
//        item {
//            ElevatedCard(
//                modifier = Modifier.fillMaxWidth(),
//                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
//            ) {
//                Column(modifier = Modifier.padding(8.dp)) {
//                    Text(
//                        text = "Resumen del Pedido",
//                        style = MaterialTheme.typography.titleMedium,
//                        fontWeight = FontWeight.Bold
//                    )
//                    Spacer(Modifier.height(8.dp))
//                    Text("Cliente: ${state.clienteSeleccionado?.nombre ?: "N/A"}")
//                    Text("Serie: ${state.serieSeleccionada?.codigo_serie ?: "N/A"}")
//                    Text("Folio: ${state.folioAsignado ?: "N/A"}")
//                    Text("plazo: ${if(state.plazoSeleccionado.toInt()==0) "Contado" else "Crédito (${state.plazoSeleccionado.toInt()})" }")
//                }
//            }
//        }
//
//        item {
//
//            // ðŸ“ ÁREA DE TEXTO PARA OBSERVACIONES
//            OutlinedTextField(
//                value = state.observacion, // <-- Usa el valor del estado
//                onValueChange = viewModel::updateObservacion, // <-- Usa la nueva función
//                label = { Text("Observaciones del Pedido") },
//                placeholder = { Text("Escribe cualquier nota o indicación especial aquí...") },
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .heightIn(
//                        min = 100.dp,
//                        max = 200.dp
//                    ), // Altura adaptable para área de texto
//                singleLine = false, // Permite múltiples líneas
//                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
//            )
//
//
//        }
//
//        item {
//            TipoDocumentoSelection(state = state, viewModel = viewModel)
//        }
//        // Líneas del pedido
//
//            // --- SELECTOR DE VISTA ---
//            item {
//                Text("Visualizar por:", style = MaterialTheme.typography.labelLarge)
//                SingleChoiceSegmentedButtonRow(
//                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
//                ) {
//                    opciones.forEachIndexed { index, label ->
//                        SegmentedButton(
//                            shape = SegmentedButtonDefaults.itemShape(index = index, count = opciones.size),
//                            onClick = { vistaSeleccionada = label },
//                            selected = vistaSeleccionada == label
//                        ) {
//                            Text(label)
//                        }
//                    }
//                }
//            }
//
//            // --- CONTENIDO DINÁMICO SEGÚN EL SELECTOR ---
//            when (vistaSeleccionada) {
//                "Productos" -> {
//                    items(state.lineasPedido) { linea ->
//                        ElevatedCard(
//                            modifier = Modifier.fillMaxWidth(),
//                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
//                        ) {
//                            Column(modifier = Modifier.padding(12.dp)) {
//                                Text(
//                                    text = "${linea.cantidad.toInt()} × ${linea.producto.nombre}",
//                                    style = MaterialTheme.typography.bodyLarge,
//                                    fontWeight = FontWeight.SemiBold
//                                )
//                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
//                                    Text(
//                                        text = "Marca: ${linea.producto.marcaNombre ?: "N/A"}",
//                                        style = MaterialTheme.typography.bodySmall
//                                    )
//                                    Text(
//                                        text = "Total: ${linea.total.toCurrency()}",
//                                        style = MaterialTheme.typography.bodyMedium,
//                                        color = MaterialTheme.colorScheme.primary,
//                                        fontWeight = FontWeight.Bold
//                                    )
//                                }
//                            }
//                        }
//                    }
//                }
//
//                "Marcas" -> {
//                    items(analisisMarca) { grupo ->
//                        ResumenGrupoCard(grupo)
//                    }
//                }
//
//                "Categorías" -> {
//                    items(analisisCat) { grupo ->
//                        ResumenGrupoCard(grupo)
//                    }
//                }
//            }
//
//            // --- SECCIÓN DE TOTALES FINALES (Siempre visible al final) ---
//            item {
//                Spacer(Modifier.height(16.dp))
//                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
//                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
//                    ResumenTotalRow("Subtotal", state.subtotalBruto)
//                    ResumenTotalRow("Descuento", state.totalDescuento)
//                    ResumenTotalRow("Impuestos", state.totalImpuestos)
//                    Text(
//                        text = "Total Final: ${state.totalPedido.toCurrency()}",
//                        style = MaterialTheme.typography.headlineSmall,
//                        color = MaterialTheme.colorScheme.primary,
//                        modifier = Modifier.align(Alignment.End)
//                    )
//                }
//
//        }
//    }
//}


//@Composable
//fun ResumenGrupoItem(grupo: ResumenGrupo) {
//    var expandido by remember { mutableStateOf(false) }
//
//    Card(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(vertical = 4.dp),
//        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
//        colors = CardDefaults.cardColors(containerColor = Color.White)
//    ) {
//        Column {
//            // Cabecera del Grupo
//            Row(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .clickable { expandido = !expandido }
//                    .padding(16.dp),
//                verticalAlignment = Alignment.CenterVertically
//            ) {
//                Column(modifier = Modifier.weight(1f)) {
//                    Text(
//                        text = grupo.nombre,
//                        style = MaterialTheme.typography.titleMedium,
//                        fontWeight = FontWeight.Bold
//                    )
//                    Text(
//                        text = "${grupo.productos.size} productos - ${String.format("%.2f", grupo.pesoTotal)} kg",
//                        style = MaterialTheme.typography.bodySmall,
//                        color = Color.Gray
//                    )
//                }
//                Text(
//                    text = grupo.montoTotal.toCurrency(),
//                    style = MaterialTheme.typography.titleMedium,
//                    color = MaterialTheme.colorScheme.primary,
//                    fontWeight = FontWeight.Bold
//                )
//            }
//
//            // Detalle de productos (solo si está expandido)
//            if (expandido) {
//                Divider(modifier = Modifier.padding(horizontal = 16.dp))
//                Column(modifier = Modifier.padding(16.dp)) {
//                    grupo.productos.forEach { producto ->
//                        Row(
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .padding(vertical = 4.dp),
//                            horizontalArrangement = Arrangement.SpaceBetween
//                        ) {
//                            Text(
//                                text = "${producto.cantidad.toInt()}x ${producto.productoNombre}",
//                                style = MaterialTheme.typography.bodyMedium,
//                                modifier = Modifier.weight(1f)
//                            )
//                            Text(
//                                text = producto.total.toCurrency(),
//                                style = MaterialTheme.typography.bodyMedium
//                            )
//                        }
//                    }
//                }
//            }
//        }
//    }
//}
//
//@Composable
//fun ResumenGrupoCard(grupo: ResumenGrupo) {
//    ElevatedCard(
//        modifier = Modifier.fillMaxWidth(),
//        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
//    ) {
//        Row(
//            modifier = Modifier.padding(16.dp),
//            horizontalArrangement = Arrangement.SpaceBetween,
//            verticalAlignment = Alignment.CenterVertically
//        ) {
//            Column(modifier = Modifier.weight(1f)) {
//                Text(grupo.nombre, style = MaterialTheme.typography.titleMedium)
//                Text("Suma de Pesos: ${"%.2f".format(grupo.pesoTotal)} kg", style = MaterialTheme.typography.bodySmall)
//            }
//            Text(
//                text = "${grupo.cantidadTotal.toInt()} unds.",
//                style = MaterialTheme.typography.titleLarge,
//                color = MaterialTheme.colorScheme.secondary
//            )
//        }
//    }
//}
//
//@Composable
//fun ResumenTotalRow(label: String, amount: Double) {
//    Row(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(vertical = 4.dp),
//        horizontalArrangement = Arrangement.SpaceBetween
//    ) {
//        Text(
//            text = label,
//            style = MaterialTheme.typography.bodyLarge,
//            color = MaterialTheme.colorScheme.onSurfaceVariant
//        )
//        Text(
//            text = amount.toCurrency(), // Usando tu extensiÃ³n .toCurrency()
//            style = MaterialTheme.typography.bodyLarge,
//            fontWeight = FontWeight.Medium
//        )
//    }
//}

@Composable
fun Step5_Exito(
    viewModel: NuevoPedidoViewModel,
    onFinish: () -> Unit,
    onPrint: (Long) -> Unit, // <--- Recibe el callback
) {
    val state by viewModel.state.collectAsState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Éxito",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "¡Pedido registrado exitosamente!",
            style = MaterialTheme.typography.headlineSmall
        )
        Text("Folio: ${state.serieSeleccionada?.codigo_serie}-${state.folioAsignado}")

        Spacer(modifier = Modifier.height(32.dp))

        // --- BOTÓN IMPRIMIR TICKET ---
        Button(
            onClick = {
                state.pedidoCreadoId?.let { id ->
                    onPrint(id) // Navega al ticket con el ID
                }
            },
            modifier = Modifier.fillMaxWidth(0.8f).height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Icon(Icons.Default.Print, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Imprimir / Ver Ticket")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- BOTÓN SALIR ---
        OutlinedButton(
            onClick = {
                viewModel.reiniciarFlujo()
                onFinish()
            },
            modifier = Modifier.fillMaxWidth(0.8f).height(50.dp)
        ) {
            Text("Terminar y Salir")
        }
    }

}// Dentro de NuevoPedidoScreen.kt (Asegúrate de que este código exista)


@Composable
fun DatoComponente(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

// Composable auxiliar para las filas de totales
@Composable
fun RowTotal(
    label: String,
    amount: String,
    style: androidx.compose.ui.text.TextStyle,
    fontWeight: FontWeight = FontWeight.Normal,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = style,
            fontWeight = fontWeight
        )
        Text(
            amount,
            style = style,
            fontWeight = fontWeight,
            color = color
        )
    }
}


@Composable
fun DetalleEnLinea(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}
// Reemplaza el bloque de selección anterior con este composable estándar:

@Composable
fun TipoDocumentoSelection(
    state: NuevoPedidoState,
    viewModel: NuevoPedidoViewModel,
) {
    Text(
        text = "Tipo de Documento",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround // Distribuye el espacio
    ) {
        // Opción Factura
        Row(
            modifier = Modifier
                .weight(0.5f)
                .clickable { viewModel.setTipoDocumento(TipoDocumento.FACTURA) }
                .padding(vertical = 8.dp), // Añade padding para área de clic
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = state.tipoDocumento == TipoDocumento.FACTURA,
                onClick = { viewModel.setTipoDocumento(TipoDocumento.FACTURA) }
            )
            Spacer(Modifier.width(4.dp))
            Text("Factura", style = MaterialTheme.typography.bodyLarge)
        }

        // Opción Remisión
        Row(
            modifier = Modifier
                .weight(0.5f)
                .clickable { viewModel.setTipoDocumento(TipoDocumento.REMISION) }
                .padding(vertical = 8.dp), // Añade padding para área de clic
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = state.tipoDocumento == TipoDocumento.REMISION,
                onClick = { viewModel.setTipoDocumento(TipoDocumento.REMISION) }
            )
            Spacer(Modifier.width(4.dp))
            Text("Remisión", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

fun Double.toCurrency(): String = "$${String.format("%.2f", this)}"



