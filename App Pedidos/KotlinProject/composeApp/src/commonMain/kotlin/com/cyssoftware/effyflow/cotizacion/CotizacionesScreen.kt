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
import kotlinx.serialization.json.*
import java.time.LocalDate

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
    val client = remember { cotizacionesSyncClient(getToken, saveTokens) { "${baseUrl.trimEnd('/')}/api/auth/refresh" } }
    DisposableEffect(client) { onDispose { client.close() } }
    var revision by remember { mutableStateOf(repository.revisionActiva()) }
    var quotes by remember(usuarioId) { mutableStateOf(repository.borradores(usuarioId)) }
    var selectedQuote by remember { mutableStateOf<CotizacionLocal?>(null) }
    var message by remember { mutableStateOf("") }
    var isNew by remember { mutableStateOf(false) }

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
            }
            revision?.let { Text("Revisión activa: ${it.id} · esquema ${it.versionEsquema} · SHA-256 ${it.huellaSha256.take(12)}…") }
                ?: Text("Sin revisión comercial válida. Descárgala antes de capturar.")
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
            if (isNew || selectedQuote != null) {
                CotizacionEditor(repository, revision, usuarioId, agenteId, selectedQuote, onSaved = {
                    quotes = repository.borradores(usuarioId); isNew = false; selectedQuote = null; message = "Borrador guardado localmente."
                })
            } else {
                Text("Borradores locales", style = MaterialTheme.typography.titleMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quotes, key = { it.uuid }) { quote ->
                        ElevatedCard(Modifier.fillMaxWidth().clickable { selectedQuote = quote }) {
                            Column(Modifier.padding(14.dp)) {
                                Text("${quote.documento} · Cliente ${quote.clienteId} · ${quote.subtotalReferencial}")
                                Text("${quote.estadoLocal} · ${quote.fechaComercial} · revisión ${quote.revisionId.take(8)}…")
                                Text("UUID ${quote.uuid}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CotizacionEditor(repository: CotizacionRepository, revision: PaqueteRevisionComercial?, usuarioId: Long,
                             agenteId: Long?, original: CotizacionLocal?, onSaved: () -> Unit) {
    val pinnedRevision = if (original != null) repository.revision(original.revisionId) else revision
    val data = pinnedRevision?.datosComerciales
    val quoteLines = remember(original?.uuid) { androidx.compose.runtime.mutableStateListOf<RenglonCotizacion>().also { it.addAll(original?.renglones.orEmpty()) } }
    var editingLineIndex by remember(original?.uuid) { mutableStateOf(if (original?.renglones?.isNotEmpty() == true) 0 else null) }
    val clients = data?.get("clientes_lista")?.jsonArray.orEmpty().filter { it.jsonObject["activo"]?.jsonPrimitive?.booleanOrNull != false }
    val products = data?.get("productos")?.jsonArray.orEmpty().filter { it.jsonObject["activo"]?.jsonPrimitive?.booleanOrNull != false }
    val presentations = data?.get("presentaciones")?.jsonArray.orEmpty()
    var clientId by remember(original) { mutableStateOf(original?.clienteId) }
    var productId by remember(original) { mutableStateOf(original?.renglones?.firstOrNull()?.productoId) }
    var presentationId by remember(original) { mutableStateOf(original?.renglones?.firstOrNull()?.presentacionId) }
    var quantity by remember(original) { mutableStateOf(original?.renglones?.firstOrNull()?.cantidad ?: "1") }
    var discount by remember(original) { mutableStateOf(original?.renglones?.firstOrNull()?.descuentoSolicitado ?: "0") }
    var date by remember(original) { mutableStateOf(original?.fechaComercial ?: LocalDate.now().toString()) }
    var document by remember(original) { mutableStateOf(original?.documento ?: "FACTURA") }
    var notes by remember(original) { mutableStateOf(original?.observaciones.orEmpty()) }
    var clientExpanded by remember { mutableStateOf(false) }
    var productExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (original == null) "Nueva cotización" else "Editar borrador", style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { clientExpanded = true }, enabled = clients.isNotEmpty()) {
                Text(clients.firstOrNull { it.jsonObject.long("id_cliente") == clientId }?.jsonObject?.string("nombre") ?: "Seleccionar cliente")
            }
            DropdownMenu(clientExpanded, { clientExpanded = false }) {
                clients.forEach { row -> DropdownMenuItem(text = { Text(row.jsonObject.string("nombre") ?: "Cliente") }, onClick = {
                    clientId = row.jsonObject.long("id_cliente"); clientExpanded = false
                }) }
            }
        }
        Box {
            OutlinedButton(onClick = { productExpanded = true }, enabled = products.isNotEmpty()) {
                Text(products.firstOrNull { it.jsonObject.long("id_producto") == productId }?.jsonObject?.string("nombre") ?: "Seleccionar artículo")
            }
            DropdownMenu(productExpanded, { productExpanded = false }) {
                products.forEach { row -> DropdownMenuItem(text = { Text("${row.jsonObject.string("codigo") ?: ""} ${row.jsonObject.string("nombre") ?: "Artículo"}") }, onClick = {
                    productId = row.jsonObject.long("id_producto"); presentationId = null; productExpanded = false
                }) }
            }
        }
        val chosenClient = clients.firstOrNull { it.jsonObject.long("id_cliente") == clientId }?.jsonObject
        val chosenProduct = products.firstOrNull { it.jsonObject.long("id_producto") == productId }?.jsonObject
        val prodPresentations = presentations.filter { it.jsonObject.long("producto_id") == productId }
        val calculation = if (pinnedRevision != null && clientId != null && productId != null) runCatching {
            repository.calcularPrecio(pinnedRevision.id, agenteId, checkNotNull(clientId), checkNotNull(productId),
                presentationId, quantity, date, discount)
        }.getOrNull() else null
        val projectedTotal = if (calculation != null) runCatching {
            val projected = quoteLines.toMutableList()
            val activeLine = RenglonCotizacion(checkNotNull(productId), presentationId, quantity,
                calculation.offeredUnitPrice.toPlainString(), discount)
            if (editingLineIndex != null && editingLineIndex!! in projected.indices) projected[editingLineIndex!!] = activeLine
            else projected.add(activeLine)
            projected.fold(java.math.BigDecimal.ZERO) { sum, line ->
                sum + repository.calcularPrecio(pinnedRevision!!.id, agenteId, checkNotNull(clientId), line.productoId,
                    line.presentacionId, line.cantidad, date, line.descuentoSolicitado).netLineAmount
            }
        }.getOrNull() else null
        if (prodPresentations.isNotEmpty()) {
            Text("Presentación disponible en revisión:")
            prodPresentations.forEach { row ->
                val item = row.jsonObject
                Row {
                    RadioButton(selected = presentationId == item.long("id_presentacion"), onClick = { presentationId = item.long("id_presentacion") })
                    Text(item.string("descripcion") ?: "Presentación ${item.long("id_presentacion")}", Modifier.padding(top = 12.dp))
                }
            }
        }
        Text("Precio de lista: ${calculation?.listUnitPrice?.toPlainString() ?: "—"}")
        if (calculation != null) {
            val volumeText = if (calculation.volumeApplied) "Volumen aplicado · regla ${calculation.volumeRuleId}" else "Sin tramo de volumen"
            val promotionText = if (calculation.promotionApplied) "Promoción ${calculation.promotionId} · prioridad ${calculation.promotionPriority}" else "Sin promoción aplicable"
            Text("$volumeText · $promotionText")
            Text("Factor ${calculation.presentationFactor.toPlainString()} · cantidad base ${calculation.baseQuantity.toPlainString()}")
            Text("Precio unitario referencial: ${calculation.offeredUnitPrice.toPlainString()}")
            Text("Descuento: ${calculation.manualDiscountPercent.toPlainString()}% · ${calculation.manualDiscountAmount.toPlainString()}")
            Text("Importe referencial del renglón: ${calculation.netLineAmount.toPlainString()}")
            projectedTotal?.let { Text("Total comercial referencial: ${it.toPlainString()}") }
        } else if (productId != null && clientId != null) {
            Text("No se puede resolver el precio con esta revisión y los datos capturados.", color = MaterialTheme.colorScheme.error)
        }
        if (quoteLines.isNotEmpty()) {
            Text("Renglones guardados: ${quoteLines.size}")
            quoteLines.forEachIndexed { index, line ->
                Text("${index + 1}. Artículo ${line.productoId} · ${line.cantidad} × ${line.precioReferencia}",
                    Modifier.clickable {
                        editingLineIndex = index; productId = line.productoId; presentationId = line.presentacionId
                        quantity = line.cantidad; discount = line.descuentoSolicitado
                    })
            }
            TextButton(onClick = { editingLineIndex = null; productId = null; presentationId = null; quantity = "1"; discount = "0" }) {
                Text("Agregar renglón")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(quantity, { quantity = it }, label = { Text("Cantidad") }, modifier = Modifier.weight(1f))
            OutlinedTextField(discount, { discount = it }, label = { Text("Descuento %") }, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(date, { date = it }, label = { Text("Fecha comercial (AAAA-MM-DD)") })
        Row {
            FilterChip(selected = document == "FACTURA", onClick = { document = "FACTURA" }, label = { Text("FACTURA") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = document == "REMISION", onClick = { document = "REMISION" }, label = { Text("REMISION") })
        }
        OutlinedTextField(notes, { notes = it }, label = { Text("Observaciones") }, modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = pinnedRevision != null && agenteId != null && clientId != null && productId != null && calculation != null, onClick = {
            runCatching {
                val currentLine = RenglonCotizacion(checkNotNull(productId), presentationId, quantity,
                    checkNotNull(calculation).offeredUnitPrice.toPlainString(), discount, pinnedRevision!!.id, pinnedRevision.huellaSha256)
                val lines = quoteLines.toMutableList()
                val at = editingLineIndex
                if (at != null && at in lines.indices) lines[at] = currentLine else lines.add(currentLine)
                repository.guardarBorrador(usuarioId, checkNotNull(agenteId), checkNotNull(clientId), date,
                    document, notes.takeIf { it.isNotBlank() }, lines, original?.uuid)
            }.onSuccess { onSaved() }.onFailure { error = it.message ?: "No se guardó el borrador." }
        }) { Text("Guardar borrador local") }
        if (chosenProduct != null) Text("Artículo comercial${if (chosenProduct["tipo"]?.jsonPrimitive?.content?.contains("KIT", true) == true) " · Kit" else ""}; composición e impuestos se determinan en ERP.")
    }
}

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
