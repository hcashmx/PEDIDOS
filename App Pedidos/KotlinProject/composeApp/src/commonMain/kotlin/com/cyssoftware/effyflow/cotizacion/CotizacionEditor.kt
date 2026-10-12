package com.cyssoftware.effyflow.cotizacion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cyssoftware.effyflow.db.Clientes
import com.cyssoftware.effyflow.db.Productos
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.LocalDate

/** Cotizacion capture keeps the familiar Pedido search/list/line workflow, with quote-specific persistence and prices. */
@Composable
internal fun CotizacionEditor(
    repository: CotizacionRepository,
    revision: PaqueteRevisionComercial?,
    usuarioId: Long,
    agenteId: Long?,
    original: CotizacionLocal?,
    onCancel: () -> Unit,
    onSaved: (uuid: String, sendNow: Boolean) -> Unit
) {
    val pinnedRevision = remember(original?.revisionId, revision?.id) {
        if (original != null) repository.revision(original.revisionId) else revision
    }
    val pricing = remember(pinnedRevision?.id) { pinnedRevision?.let { CotizacionPricing(it.datosComerciales) } }
    val presentations = pinnedRevision?.datosComerciales?.get("presentaciones")?.jsonArray.orEmpty()
    val quoteLines = remember(original?.uuid) {
        mutableStateListOf<RenglonCotizacion>().also { it.addAll(original?.renglones.orEmpty()) }
    }
    var editingLineIndex by remember(original?.uuid) {
        mutableStateOf(if (original?.renglones?.isNotEmpty() == true) 0 else null)
    }
    var selectedClient by remember(original?.clienteId) {
        mutableStateOf(original?.clienteId?.let(repository::clientePorId))
    }
    var selectedClientId by remember(original?.clienteId) { mutableStateOf(original?.clienteId) }
    var selectedProduct by remember(original?.uuid) {
        mutableStateOf(original?.renglones?.firstOrNull()?.productoId?.let(repository::productoPorId))
    }
    var productId by remember(original?.uuid) { mutableStateOf(original?.renglones?.firstOrNull()?.productoId) }
    var presentationId by remember(original?.uuid) { mutableStateOf(original?.renglones?.firstOrNull()?.presentacionId) }
    var quantity by remember(original?.uuid) { mutableStateOf(original?.renglones?.firstOrNull()?.cantidad ?: "1") }
    var discount by remember(original?.uuid) { mutableStateOf(original?.renglones?.firstOrNull()?.descuentoSolicitado ?: "0") }
    var date by remember(original?.uuid) { mutableStateOf(original?.fechaComercial ?: java.time.LocalDate.now().toString()) }
    var document by remember(original?.uuid) { mutableStateOf(original?.documento ?: "FACTURA") }
    var notes by remember(original?.uuid) { mutableStateOf(original?.observaciones.orEmpty()) }
    var clientQuery by remember(original?.uuid) { mutableStateOf("") }
    var clientResults by remember { mutableStateOf(emptyList<Clientes>()) }
    var productQuery by remember(original?.uuid) { mutableStateOf("") }
    var productResults by remember { mutableStateOf(emptyList<Productos>()) }
    var error by remember { mutableStateOf("") }
    val clientFocus = remember { FocusRequester() }
    val productFocus = remember { FocusRequester() }
    val quantityFocus = remember { FocusRequester() }
    val clientId = selectedClientId

    LaunchedEffect(clientQuery, pinnedRevision?.id, agenteId, clientId) {
        clientResults = emptyList()
        val term = clientQuery.trim()
        if (clientId == null && searchable(term) && pinnedRevision != null && agenteId != null) {
            delay(180)
            clientResults = runCatching { repository.buscarClientesCaptura(pinnedRevision.id, agenteId, term) }
                .getOrElse { error = it.message ?: "No se pudieron buscar clientes."; emptyList() }
        }
    }
    LaunchedEffect(productQuery, pinnedRevision?.id, clientId) {
        productResults = emptyList()
        val term = productQuery.trim()
        if (clientId != null && searchable(term) && pinnedRevision != null) {
            delay(180)
            productResults = runCatching { repository.buscarProductosCaptura(pinnedRevision.id, term) }
                .getOrElse { error = it.message ?: "No se pudieron buscar artículos."; emptyList() }
        }
    }
    LaunchedEffect(clientId, selectedProduct) {
        if (clientId == null) clientFocus.requestFocus() else if (selectedProduct == null) productFocus.requestFocus()
    }
    LaunchedEffect(productId) { if (productId != null) quantityFocus.requestFocus() }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (original == null) "Nueva Cotización" else "Editar Cotización", style = MaterialTheme.typography.titleMedium)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Información de la Cotización", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("Cliente: ${selectedClient?.displayName() ?: selectedClientId?.let { "ID $it · nombre no disponible en catálogo local" } ?: "pendiente de seleccionar"}")
                Text("Documento: ${if (document == "FACTURA") "FACTURA" else "REMISIÓN"} · fecha $date")
            }
        }
        if (pinnedRevision == null) {
            Text("No está disponible la revisión comercial fijada a este borrador.", color = MaterialTheme.colorScheme.error)
        } else {
            Text("Revisión comercial: ${pinnedRevision.id}", style = MaterialTheme.typography.bodySmall)
        }

        ClienteSelectorCotizacion(
            selected = selectedClient,
            selectedId = selectedClientId,
            query = clientQuery,
            results = clientResults,
            focusRequester = clientFocus,
            onQuery = { clientQuery = it; error = "" },
            onSelect = { selectedClient = it; selectedClientId = it.id_cliente; clientQuery = ""; clientResults = emptyList(); error = "" },
            onChange = { selectedClient = null; selectedClientId = null; clientQuery = ""; clientResults = emptyList() }
        )

        if (clientId != null) {
            ProductoBuscadorCotizacion(
                selected = selectedProduct,
                query = productQuery,
                results = productResults,
                revisionId = pinnedRevision?.id,
                focusRequester = productFocus,
                onQuery = { productQuery = it; error = "" },
                onSelect = { product ->
                    selectedProduct = product
                    productId = product.id_producto
                    presentationId = null
                    productQuery = ""
                    productResults = emptyList()
                    error = ""
                },
                onChange = { selectedProduct = null; productId = null; presentationId = null; productQuery = "" }
            )
        }

        val productPresentations = presentations.filter { it.jsonObject.long("producto_id") == productId }
        val calculation = if (pricing != null && clientId != null && productId != null) runCatching {
            pricing.calculate(clientId, agenteId, productId!!, presentationId,
                quantity.replace(',', '.').toBigDecimal(), LocalDate.parse(date), discount.replace(',', '.').toBigDecimal())
        }.getOrNull() else null
        val projectedTotal = if (calculation != null && pricing != null && clientId != null) runCatching {
            val projected = quoteLines.toMutableList()
            val active = RenglonCotizacion(productId!!, presentationId, quantity.replace(',', '.'),
                calculation.offeredUnitPrice.toPlainString(), discount.replace(',', '.'))
            if (editingLineIndex != null && editingLineIndex!! in projected.indices) projected[editingLineIndex!!] = active
            else projected.add(active)
            projected.fold(BigDecimal.ZERO) { sum, line ->
                sum + pricing.calculate(clientId, agenteId, line.productoId, line.presentacionId,
                    line.cantidad.toBigDecimal(), LocalDate.parse(date), line.descuentoSolicitado.toBigDecimal()).netLineAmount
            }
        }.getOrNull() else null
        val quoteTotal = if (pricing != null && clientId != null && quoteLines.isNotEmpty()) runCatching {
            quoteLines.fold(BigDecimal.ZERO) { sum, line ->
                sum + pricing.calculate(clientId, agenteId, line.productoId, line.presentacionId,
                    line.cantidad.toBigDecimal(), LocalDate.parse(date), line.descuentoSolicitado.toBigDecimal()).netLineAmount
            }
        }.getOrNull() else null

        if (productId != null && productPresentations.isNotEmpty()) {
            Text("Presentación", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = presentationId == null, onClick = { presentationId = null })
                Text("Unidad base")
            }
            productPresentations.forEach { row ->
                val item = row.jsonObject
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = presentationId == item.long("id_presentacion"), onClick = { presentationId = item.long("id_presentacion") })
                    Text("${item.string("descripcion") ?: "Presentación ${item.long("id_presentacion")}"} · factor ${item["factor_conversion"]?.jsonPrimitive?.content ?: "1"}")
                }
            }
        }

        if (calculation != null) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Precio de lista: ${calculation.listUnitPrice.toPlainString()}")
                    Text(if (calculation.volumeApplied) "Precio por volumen aplicado" else "Sin tramo de volumen")
                    Text(if (calculation.promotionApplied) "Promoción aplicada · prioridad ${calculation.promotionPriority}" else "Sin promoción aplicable")
                    Text("Precio unitario referencial: ${calculation.offeredUnitPrice.toPlainString()}")
                    Text("Descuento solicitado: ${calculation.manualDiscountPercent.toPlainString()}% · ${calculation.manualDiscountAmount.toPlainString()}")
                    Text("Importe referencial del renglón: ${calculation.netLineAmount.toPlainString()}")
                    projectedTotal?.let { Text("Total comercial referencial: ${it.toPlainString()}", style = MaterialTheme.typography.titleSmall) }
                }
            }
        } else if (productId != null && clientId != null) {
            Text("No se puede resolver el precio con la revisión fijada y estos datos.", color = MaterialTheme.colorScheme.error)
        }

        if (quoteLines.isNotEmpty()) {
            Text("Renglones (${quoteLines.size})", style = MaterialTheme.typography.titleMedium)
            quoteTotal?.let { Text("Total comercial referencial: ${it.toPlainString()}", style = MaterialTheme.typography.titleSmall) }
            quoteLines.forEachIndexed { index, line ->
                val product = repository.productoPorId(line.productoId)
                val lineAmount = if (pricing != null && clientId != null) runCatching {
                    pricing.calculate(clientId, agenteId, line.productoId, line.presentacionId,
                        line.cantidad.toBigDecimal(), LocalDate.parse(date), line.descuentoSolicitado.toBigDecimal()).netLineAmount.toPlainString()
                }.getOrNull() else null
                ElevatedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("${index + 1}. ${product?.displayName() ?: "Artículo ${line.productoId}"}") },
                        supportingContent = { Text("Código ${product?.codigo ?: line.productoId} · cantidad ${line.cantidad} · precio ${line.precioReferencia} · descuento ${line.descuentoSolicitado}% · importe ${lineAmount ?: "—"}") },
                        trailingContent = {
                            Row {
                                TextButton(onClick = {
                                    editingLineIndex = index
                                    selectedProduct = product
                                    productId = line.productoId
                                    presentationId = line.presentacionId
                                    quantity = line.cantidad
                                    discount = line.descuentoSolicitado
                                }) { Text("Editar") }
                                TextButton(onClick = {
                                    quoteLines.removeAt(index)
                                    if (editingLineIndex == index) {
                                        editingLineIndex = null; selectedProduct = null; productId = null; presentationId = null
                                    } else if (editingLineIndex != null && editingLineIndex!! > index) editingLineIndex = editingLineIndex!! - 1
                                }) { Text("Quitar") }
                            }
                        }
                    )
                }
            }
        }

        if (productId != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = decimalInput(it); error = "" },
                    label = { Text("Cantidad") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f).focusRequester(quantityFocus)
                )
                OutlinedTextField(
                    value = discount,
                    onValueChange = { discount = decimalInput(it); error = "" },
                    label = { Text("Descuento %") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = calculation != null, onClick = {
                    runCatching {
                        val line = RenglonCotizacion(productId!!, presentationId, quantity.replace(',', '.'),
                            calculation!!.offeredUnitPrice.toPlainString(), discount.replace(',', '.'), pinnedRevision!!.id, pinnedRevision.huellaSha256)
                        if (editingLineIndex != null && editingLineIndex!! in quoteLines.indices) quoteLines[editingLineIndex!!] = line
                        else quoteLines.add(line)
                        editingLineIndex = null; selectedProduct = null; productId = null; presentationId = null
                        productQuery = ""; quantity = "1"; discount = "0"; error = ""
                    }.onFailure { error = it.message ?: "No se pudo guardar el renglón." }
                }) { Text(if (editingLineIndex == null) "Agregar renglón" else "Actualizar renglón") }
                if (editingLineIndex != null) TextButton(onClick = {
                    editingLineIndex = null; selectedProduct = null; productId = null; presentationId = null; quantity = "1"; discount = "0"
                }) { Text("Cancelar edición") }
            }
        }

        OutlinedTextField(date, { date = it }, label = { Text("Fecha comercial (AAAA-MM-DD)") }, singleLine = true)
        Row {
            FilterChip(selected = document == "FACTURA", onClick = { document = "FACTURA" }, label = { Text("FACTURA") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = document == "REMISION", onClick = { document = "REMISION" }, label = { Text("REMISIÓN") })
        }
        OutlinedTextField(notes, { notes = it }, label = { Text("Observaciones") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Cancelar") }
            Button(enabled = pinnedRevision != null && agenteId != null && clientId != null && quoteLines.isNotEmpty(), onClick = {
                runCatching {
                    repository.guardarBorrador(usuarioId, checkNotNull(agenteId), checkNotNull(clientId), date,
                        document, notes.takeIf(String::isNotBlank), quoteLines.toList(), original?.uuid)
                }.onSuccess { onSaved(it, false) }.onFailure { error = it.message ?: "No se guardó el borrador." }
            }) { Text("Guardar borrador") }
            Button(enabled = pinnedRevision != null && agenteId != null && clientId != null && quoteLines.isNotEmpty(), onClick = {
                runCatching {
                    repository.guardarBorrador(usuarioId, checkNotNull(agenteId), checkNotNull(clientId), date,
                        document, notes.takeIf(String::isNotBlank), quoteLines.toList(), original?.uuid)
                }.onSuccess { onSaved(it, true) }.onFailure { error = it.message ?: "No se pudo preparar la Cotización." }
            }) { Text("Enviar Cotización") }
        }
        Text("Importes referenciales según la revisión fijada. La autorización definitiva corresponde al ERP.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ClienteSelectorCotizacion(
    selected: Clientes?, selectedId: Long?, query: String, results: List<Clientes>, focusRequester: FocusRequester,
    onQuery: (String) -> Unit, onSelect: (Clientes) -> Unit, onChange: () -> Unit
) {
    if (selected == null && selectedId == null) {
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            label = { Text("Buscar cliente por nombre, razón social, RFC o código") },
            supportingText = { Text("Hasta 50 coincidencias autorizadas para el agente.") },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
        )
        if (results.isNotEmpty()) ElevatedCard(Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.heightIn(max = 250.dp)) {
                items(results, key = { it.id_cliente }) { client ->
                    ListItem(
                        headlineContent = { Text(client.displayName()) },
                        supportingContent = { Text("${client.nombre} · RFC ${client.rfc?.takeIf(String::isNotBlank) ?: "sin RFC"} · código ${client.id_cliente}") },
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(client) }
                    )
                    HorizontalDivider()
                }
            }
        } else if (query.isNotBlank() && searchable(query.trim())) {
            Text("No hay coincidencias autorizadas en la revisión comercial.", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        ElevatedCard(Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text(selected?.displayName() ?: "Cliente ID $selectedId · nombre no disponible en catálogo local") },
                supportingContent = { Text(if (selected != null) "${selected.nombre} · RFC ${selected.rfc?.takeIf(String::isNotBlank) ?: "sin RFC"} · código ${selected.id_cliente}" else "Se conserva el cliente guardado en el borrador.") },
                trailingContent = { TextButton(onClick = onChange) { Text("Cambiar") } }
            )
        }
    }
}

@Composable
private fun ProductoBuscadorCotizacion(
    selected: Productos?, query: String, results: List<Productos>, revisionId: String?, focusRequester: FocusRequester,
    onQuery: (String) -> Unit, onSelect: (Productos) -> Unit, onChange: () -> Unit
) {
    if (selected == null) {
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            label = { Text("Buscar artículo por código o descripción") },
            supportingText = { Text("Artículos de la revisión ${revisionId?.take(8) ?: "no disponible"}; los Kits se cotizan como artículo comercial.") },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
        )
        if (results.isNotEmpty()) ElevatedCard(Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(results, key = { it.id_producto }) { product ->
                    ListItem(
                        headlineContent = { Text(product.displayName()) },
                        supportingContent = { Text("Código ${product.codigo?.takeIf(String::isNotBlank) ?: product.id_producto.toString()} · ${product.tipo_articulo ?: product.tipo ?: "Artículo"}${if (product.tipo == "K") " · Kit" else ""}") },
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(product) }
                    )
                    HorizontalDivider()
                }
            }
        } else if (query.isNotBlank() && searchable(query.trim())) {
            Text("No hay coincidencias en la revisión comercial.", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        ElevatedCard(Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text(selected.displayName()) },
                supportingContent = { Text("Código ${selected.codigo?.takeIf(String::isNotBlank) ?: selected.id_producto.toString()} · ${if (selected.tipo == "K") "Kit comercial" else "Artículo comercial"}") },
                trailingContent = { TextButton(onClick = onChange) { Text("Cambiar") } }
            )
        }
    }
}

private fun Clientes.displayName(): String = razon_social?.takeIf(String::isNotBlank) ?: nombre
private fun Productos.displayName(): String = nombre?.takeIf(String::isNotBlank)
    ?: descripcion?.takeIf(String::isNotBlank) ?: "Artículo $id_producto"
private fun searchable(term: String): Boolean = term.length >= 2 || term.isNotEmpty() && term.all(Char::isDigit)
private fun decimalInput(value: String): String = value.filter { it.isDigit() || it == '.' || it == ',' }
private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
