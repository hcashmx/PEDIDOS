package com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel

import com.cyssoftware.effyflow.db.Agentes
import com.cyssoftware.effyflow.db.Clientes
import com.cyssoftware.effyflow.db.GetImpuestosParaProducto
import com.cyssoftware.effyflow.db.GetProductoDetalladoStockById
import com.cyssoftware.effyflow.db.Productos
import com.cyssoftware.effyflow.db.SearchProductosConDetalle
import com.cyssoftware.effyflow.db.SearchProductosConDetalleStock
import com.cyssoftware.effyflow.db.Series_folios
import com.cyssoftware.effyflow.db.Users
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoCalculos
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoCreditoRules
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 1. Define los pasos del Wizard
enum class PedidoStep {
    SERIE_FOLIO, CLIENTE, PRODUCTOS, RESUMEN, EXITO
}


data class ResumenGrupo(
    val nombre: String,
    val cantidadTotal: Double,
    val pesoTotal: Double,
    val productos: List<DetallePedido>,
    val montoTotal: Double // Opcional, por si quieres ver cuÃ¡nto dinero representa el grupo
)

data class DetallePedido(
    val idDetalle: Int? = null,
    val pedidoId: Int? = null,
    val productoId: Int? = null,
    val productoNombre: String? = null,
    val cantidad: Double = 0.0,
    val precioUnitario: Double = 0.0,
    val pesoUnitario: Double = 0.0,
    val categoriaNombre: String = "Sin Categoría",
    val marcaNombre: String = "Sin Marca", // AÃ±adido para soportar getResumenPorMarca()
    val impuestoTasa: Double = 0.0,
    val subtotal: Double = 0.0,
    val total: Double = 0.0
) {
    /**
     * FunciÃ³n de utilidad para calcular el total de la lÃ­nea
     * basÃ¡ndose en la lÃ³gica de tu ViewModel
     */
    fun calcularTotal(): Double {
        val montoSubtotal = cantidad * precioUnitario
        return montoSubtotal + (montoSubtotal * impuestoTasa)
    }
}

enum class TipoPedido {
    CONTADO, CREDITO, NINGUNO
}

data class ComponenteKit(
    val producto: GetProductoDetalladoStockById,
    val cantidad: Double
)

// 2. Clases para cÃ¡lculos
data class ImpuestoCalculado(
    val idImpuesto: Long?,
    val nombre: String,
    val tipoAplicacion: String?,
    val tipoFactor: String?,
    val tasa: Double,
    val base: Double,
    val monto: Double,
    val cImpuesto: String?,
    val signo: Long?,
)

data class TotalesPedido(
    val subtotalBruto: Double,
    val totalDescuento: Double,
    val totalImpuestos: Double,
    val totalNeto: Double,
)
enum class TipoDocumento {
    FACTURA, REMISION
}


data class PedidoLinea(
    val producto: SearchProductosConDetalleStock, // Usa el tipo con JOINS
    val cantidad: Double,
    val precio: Double,
    val descuento: Double,
    val subtotal: Double, // (precio * (1-desc)) * cant
    val impuestos: List<ImpuestoCalculado>, // Lista de impuestos
    val total: Double, // subtotal + impuestos
    val presentacionId: Long?,
    val almacenId: Long?,
)

// 3. Estado general del Wizard
data class NuevoPedidoState(
    val currentStep: PedidoStep = PedidoStep.SERIE_FOLIO,
    val pedidoCreadoId: Long? = null,
    val seriesDisponibles: List<Series_folios> = emptyList(), // <-- Corregido
    val clientesEncontrados: List<Clientes> = emptyList(),
    val productosEncontrados: List<SearchProductosConDetalleStock> = emptyList(),
    val productosSustitutos: List<Productos> = emptyList(),

    val isLoading: Boolean = false,
    val error: String? = null,
    val productoError: String? = null,

    val usuarioLogueado: Users? = null,
    val agenteLogueado: Agentes? = null,

    val serieSeleccionada: Series_folios? = null, // <-- Corregido
    val folioAsignado: Long? = null,
    val clienteSeleccionado: Clientes? = null,
    val direccionesDisponibles: List<PedidoRepository.DireccionCombo> = emptyList(),
    val direccionSeleccionada: PedidoRepository.DireccionCombo? = null,
    val lineasPedido: List<PedidoLinea> = emptyList(),
    val tipoPedidoSeleccionado: TipoPedido = TipoPedido.NINGUNO,
    val plazoSeleccionado: Long = 0L,
    val observacion: String = "",
    val tipoDocumento: TipoDocumento = TipoDocumento.FACTURA,
    val searchQuery: String = "",
    val eventoUI: PedidoUIEvent? = null, // <-- NUEVO CAMPO PARA EVENTOS
    val totalPedido: Double = 0.0,
    val subtotalBruto: Double = 0.0,
    val totalDescuento: Double = 0.0,
    val totalImpuestos: Double = 0.0,
    val componentesKitSeleccionado: List<ComponenteKit>? = null,
)

data class DireccionEntrega(
    val idUbicacion: Long?, // null si es la direcciÃ³n principal del cliente
    val descripcion: String,
    val calle: String,
    val cp: String
)

sealed class PedidoUIEvent {
    data class AlertaProductoExistente(val nombreProducto: String) : PedidoUIEvent()
    data object ProductoAgregadoExitosamente : PedidoUIEvent()
    // Puedes agregar más eventos como errores especÃ­ficos, etc.
}

// 4. El ViewModel compartido
class NuevoPedidoViewModel(
    private val pedidoRepository: PedidoRepository,
    private val usuarioIdLogueado: Long,
    dispatcher: CoroutineDispatcher,
) {
    private val viewModelScope = CoroutineScope(dispatcher) // SimulaciÃ³n de viewModelScope
    val componentesMostrados: List<Pair<Productos, Double>>? = null
    private val _state = MutableStateFlow(NuevoPedidoState())
    val state: StateFlow<NuevoPedidoState> = _state.asStateFlow()
    private var productSearchSequence = 0L
    private var productSearchJob: Job? = null



    init {
        loadAgenteYUsuario(usuarioIdLogueado)
    }

    fun getResumenPorMarca(): List<ResumenGrupo> {
        return _state.value.lineasPedido
            .groupBy { it.producto.marcaNombre ?: "Sin Marca" }
            .map { (marca, lineas) ->
                ResumenGrupo(
                    nombre = marca,
                    cantidadTotal = lineas.sumOf { it.cantidad },
                    pesoTotal = lineas.sumOf { (it.producto.peso ?: 0.0) * it.cantidad },
                    montoTotal = lineas.sumOf { it.total },
                    // Convertimos PedidoLinea a DetallePedido
                    productos = lineas.map { linea ->
                        DetallePedido(
                            productoId = linea.producto.id_producto.toInt(),
                            productoNombre = linea.producto.nombre,
                            cantidad = linea.cantidad,
                            precioUnitario = linea.producto.precioUnitario?: 0.0,
                            pesoUnitario = linea.producto.peso ?: 0.0,
                            categoriaNombre = linea.producto.categoriaNombre ?: "Sin Categoría",
                            marcaNombre = marca,
                            total = linea.total
                        )
                    }
                )
            }
    }

    fun getResumenPorCategoria(): List<ResumenGrupo> {
        return _state.value.lineasPedido
            .groupBy { it.producto.categoriaNombre ?: "General" }
            .map { (categoria, lineas) ->
                ResumenGrupo(
                    nombre = categoria,
                    cantidadTotal = lineas.sumOf { it.cantidad },
                    pesoTotal = lineas.sumOf { (it.producto.peso ?: 0.0) * it.cantidad },
                    montoTotal = lineas.sumOf { it.total },
                    // Convertimos PedidoLinea a DetallePedido
                    productos = lineas.map { linea ->
                        DetallePedido(
                            productoId = linea.producto.id_producto.toInt(),
                            productoNombre = linea.producto.nombre,
                            cantidad = linea.cantidad,
                            precioUnitario = linea.producto.precioUnitario?: 0.0,
                            pesoUnitario = linea.producto.peso ?: 0.0,
                            categoriaNombre = categoria,
                            marcaNombre = linea.producto.marcaNombre ?: "Sin Marca",
                            total = linea.total
                        )
                    }
                )
            }
    }

    fun retrocederPaso() {
        val pasoAnterior = when (_state.value.currentStep) {
            PedidoStep.CLIENTE -> PedidoStep.SERIE_FOLIO
            PedidoStep.PRODUCTOS -> PedidoStep.CLIENTE
            PedidoStep.RESUMEN -> PedidoStep.PRODUCTOS
            PedidoStep.EXITO -> PedidoStep.RESUMEN
            else -> PedidoStep.SERIE_FOLIO
        }

        _state.update { it.copy(currentStep = pasoAnterior) }
    }

    fun irAPaso(step: PedidoStep) {
        _state.update { it.copy(currentStep = step) }
    }

    private fun loadAgenteYUsuario(userId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val usuario = pedidoRepository.getUsuario(userId)

            val agente = pedidoRepository.getAgentePorUsuario(userId)

            if (usuario == null || agente == null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Error: Agente/Usuario no configurado."
                    )
                }
                return@launch
            }

            val series = pedidoRepository.getSeriesPorUsuario(userId)
            if (series.isEmpty()) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "No tienes series de tipo pedido asignadas."
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    isLoading = false,
                    usuarioLogueado = usuario,
                    agenteLogueado = agente,
                    seriesDisponibles = series
                )
            }
        }
    }

    //--- Eventos del Wizard ---

    fun onSerieSeleccionada(serie: Series_folios, folioManual: Long? = null) {
        viewModelScope.launch {
            val folioFinal: Long

            val esConsecutiva = (serie.es_consecutiva ?: 1L) != 0L
            if (!esConsecutiva) {
                val folio = folioManual
                if (folio == null) {
                    _state.update { it.copy(error = "La serie ${serie.codigo_serie} requiere capturar folio manual.") }
                    return@launch
                }
                Napier.d("Serie manual, validando folio: $folio")

                val existe = pedidoRepository.existePedidoConFolio(serie.id_serie, folio)
                if (existe) {
                    _state.update { it.copy(error = "El folio $folio ya existe en la serie ${serie.codigo_serie}") }
                    return@launch
                }

                folioFinal = folio
            } else {
                Napier.d("Serie consecutiva, buscando siguiente folio.")
                val siguiente = pedidoRepository.getSiguienteFolio(serie.id_serie)
                folioFinal = siguiente
            }

            _state.update {
                it.copy(
                    serieSeleccionada = serie,
                    folioAsignado = folioFinal,
                    currentStep = PedidoStep.CLIENTE,
                    error = null
                )
            }

            searchCliente("")
        }
    }

    /* fun onSerieSeleccionada(serie: Series_folios) { // <-- Corregido
         val folio = (serie.folio ?: 0) + 1
         _state.update { it.copy(
             serieSeleccionada = serie,
             folioAsignado = folio,
             currentStep = PedidoStep.CLIENTE,
         )}
     }*/

//    fun onClienteSeleccionado(cliente: Clientes, tipo: TipoPedido, plazo: Long) {
//
//        if (plazo < 0 || plazo > (cliente.plazo?.toLong() ?: 0)) {
//            _state.update {
//                it.copy(
//                    error = "El plazo ingresado ($plazo) debe ser mayor o igual a 0 y menor o igual a ${cliente.plazo}"
//                )
//            }
//            return
//        }
//        _state.update {
//            it.copy(
//                clienteSeleccionado = cliente,
//                tipoPedidoSeleccionado = tipo,
//                plazoSeleccionado = plazo, // <-- Guardamos el plazo (0 o >0)
//                error = null
//                //   currentStep = PedidoStep.PRODUCTOS,
//            )
//        }
//        cargarDireccionesCliente(cliente.id_cliente.toInt())
//    }
fun onClienteSeleccionado(cliente: Clientes, tipo: TipoPedido, plazo: Long) {

    val condicion = PedidoCreditoRules.resolverCondicion(
        plazoAutorizado = cliente.plazo,
        creditoBloqueado = cliente.credito_bloqueado == 1L,
        solicitado = tipo
    )

    // 1. Verificamos si el usuario seleccionó un cliente diferente al que ya estaba
    val esNuevoCliente = _state.value.clienteSeleccionado?.id_cliente != cliente.id_cliente

    _state.update {
        it.copy(
            clienteSeleccionado = cliente,
            tipoPedidoSeleccionado = condicion.tipo,
            plazoSeleccionado = condicion.plazo,
            error = null
        )
    }

    // 2. SOLO recargamos y reseteamos las direcciones si es la primera vez
    // que toca este cliente, o si cambiÃ³ de cliente.
    // Si solo está actualizando el Tipo de Pago o el Plazo (al darle a Continuar), esto se ignora.
    if (esNuevoCliente) {
        cargarDireccionesCliente(cliente.id_cliente.toInt())
    }
}
//fun onClienteSeleccionado(cliente: Clientes, tipo: TipoPedido, plazo: Long) {
//    // 1. ValidaciÃ³n de plazos (se mantiene igual)
//    if (plazo < 0 || plazo > (cliente.plazo?.toLong() ?: 0)) {
//        _state.update {
//            it.copy(error = "El plazo ingresado ($plazo) debe ser menor o igual a ${cliente.plazo}")
//        }
//        return
//    }
//
//    // 2. Cargamos las ubicaciones y actualizamos el estado SIN cambiar de Step
//    viewModelScope.launch {
//        try {
//            // Buscamos las direcciones en el repositorio
//            val direcciones = pedidoRepository.getDireccionesDelCliente(cliente.id_cliente)
//
//            _state.update {
//                it.copy(
//                    clienteSeleccionado = cliente,
//                    tipoPedidoSeleccionado = tipo,
//                    plazoSeleccionado = plazo,
//                    direccionesDisponibles = direcciones,
//                    direccionSeleccionada = direcciones.firstOrNull(), // Seleccionamos la matriz por defecto
//                    // ðŸš¨ IMPORTANTE: NO cambiamos el currentStep aquí ðŸš¨
//                    error = null
//                )
//            }
//        } catch (e: Exception) {
//            _state.update { it.copy(error = "Error al cargar las ubicaciones del cliente") }
//        }
//    }
//}

    // 3. NUEVA FUNCIÃ“N: Para avanzar al siguiente paso cuando el usuario confirme la ubicaciÃ³n
    fun confirmarUbicacionYContinuar() {
        _state.update {
            it.copy(currentStep = PedidoStep.PRODUCTOS)
        }
    }



    /**
     * FunciÃ³n para actualizar la direcciÃ³n cuando el usuario toca una opciÃ³n en la lista.
     */
    fun seleccionarDireccion(direccion: PedidoRepository.DireccionCombo) {
        _state.update {
            it.copy(direccionSeleccionada = direccion)
        }
    }


    // En tu ViewModel
    fun cargarDireccionesCliente(clienteId: Int) {
        viewModelScope.launch {
            val direcciones = pedidoRepository.getDireccionesDelCliente(clienteId.toLong())
            _state.update { it.copy(
                direccionesDisponibles = direcciones,
                // Pre-seleccionamos la principal (ID -1)
                direccionSeleccionada = direcciones.find { it.id == -1L }
            ) }
        }
    }

    fun onDireccionSelected(direccion: PedidoRepository.DireccionCombo) {
        _state.update { it.copy(direccionSeleccionada = direccion) }
    }

    fun finalizarSeleccionClienteYDireccion() {
        val direccion = _state.value.direccionSeleccionada
        if (direccion != null) {
            _state.update { it.copy(currentStep = PedidoStep.PRODUCTOS) }
        } else {
            _state.update { it.copy(error = "Por favor, seleccione una direcciÃ³n de entrega") }
        }
    }



    fun consultarComponentesKit(idKit: Long) {
        viewModelScope.launch {
            try {
                // 1. Obtener los datos del repositorio
                val listaComponentes = pedidoRepository.getComponentesDelKit(idKit)

                // 2. Actualizar el estado (Ahora 'componentesKitSeleccionado' sí existe)
                _state.update { it.copy(componentesKitSeleccionado = listaComponentes) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "No se pudieron cargar los componentes") }
            }
        }
    }


    // En PedidoRepository.kt


    fun cerrarDetalleKit() {
        _state.update { it.copy(componentesKitSeleccionado = null) }
    }


    fun onAddProducto(producto: SearchProductosConDetalleStock, cantidad: Double, alerta: Boolean) {
        val cliente = _state.value.clienteSeleccionado ?: return

        if (producto.activo == 0L) {
            _state.update { it.copy(productoError = "Producto descontinuado.") }
            return
        }

        if (cantidad <= 0.0) {
            _state.update { it.copy(productoError = "La cantidad debe ser mayor a 0.") }
            return
        }

        viewModelScope.launch {
            val precioInfo = pedidoRepository.getPrecioParaCliente(
                producto.id_producto,
                cliente.numero_precio_id
            )
            val precio = precioInfo?.precio ?: producto.costo_ultimo ?: 0.0
            val descuentoMax = producto.descuento_max ?: 0.0

            // ðŸ”¹ Verificar si ya existe en el pedido
            val lineasActuales = _state.value.lineasPedido.toMutableList()
            val indexExistente =
                lineasActuales.indexOfFirst { it.producto.id_producto == producto.id_producto }

            if (indexExistente >= 0) {
                // ðŸ” Ya existe â†’ sumamos la cantidad
                val lineaExistente = lineasActuales[indexExistente]
                val nuevaCantidad = lineaExistente.cantidad + cantidad
                val subtotal = PedidoCalculos.calcularSubtotal(precio, descuentoMax, nuevaCantidad)

                // Recalcular impuestos
                val impuestosDb = pedidoRepository.getImpuestosParaProducto(producto.id_producto)
                val impuestosCalculados = impuestosDb.mapNotNull { impuesto ->
                    if (impuesto.tipo_factor == "Tasa" && impuesto.activo==1L) {
                        val tasa = impuesto.tasa_o_cuota ?: 0.0
                        val monto = PedidoCalculos.calcularImpuesto(subtotal, tasa)
                        ImpuestoCalculado(
                            idImpuesto = impuesto.id_impuesto,
                            nombre = impuesto.nombre ?: "Impuesto",
                            tipoAplicacion = impuesto.tipo_aplicacion,
                            tipoFactor = impuesto.tipo_factor,
                            tasa = tasa,
                            base = subtotal,
                            monto = monto,
                            cImpuesto = impuesto.c_impuesto,
                            signo = impuesto.signo
                        )
                    } else null
                }

                val totalImpuestos = PedidoCalculos.sumarImportes(impuestosCalculados.map { it.monto })
                val total = PedidoCalculos.calcularTotal(subtotal, impuestosCalculados.map { it.monto })

                lineasActuales[indexExistente] = lineaExistente.copy(
                    cantidad = nuevaCantidad,
                    subtotal = subtotal,
                    impuestos = impuestosCalculados,
                    total = total
                )

                // ðŸš¨ Emitir evento de ALERTA: Producto ya existÃ­a (Venta emergente)
                val nuevosTotales = calcularTotalesDetallados(lineasActuales)
                if (alerta) {
                    _state.update {
                        val mantenerTopProductos = it.searchQuery == "Top Productos"
                        it.copy(
                            lineasPedido = lineasActuales,
                            totalPedido = nuevosTotales.totalNeto, // Actualizar total neto
                            subtotalBruto = nuevosTotales.subtotalBruto, // <-- NUEVO
                            totalDescuento = nuevosTotales.totalDescuento, // <-- NUEVO
                            totalImpuestos = nuevosTotales.totalImpuestos, // <-- NUEVO

                            productoError = null,
                            productosEncontrados = if (mantenerTopProductos) it.productosEncontrados else emptyList(),
                            productosSustitutos = emptyList(),
                            eventoUI = PedidoUIEvent.AlertaProductoExistente(
                                producto.nombre ?: "Producto"
                            )
                        )
                    }
                } else {
                    _state.update {
                        val mantenerTopProductos = it.searchQuery == "Top Productos"
                        it.copy(
                            lineasPedido = lineasActuales,
                            productoError = null,
                            productosEncontrados = if (mantenerTopProductos) it.productosEncontrados else emptyList(),
                            totalPedido = nuevosTotales.totalNeto, // Actualizar total neto
                            subtotalBruto = nuevosTotales.subtotalBruto, // <-- NUEVO
                            totalDescuento = nuevosTotales.totalDescuento, // <-- NUEVO
                            totalImpuestos = nuevosTotales.totalImpuestos, // <-- NUEVO
                            productosSustitutos = emptyList(),
                            eventoUI = PedidoUIEvent.ProductoAgregadoExitosamente
                        )
                    }
                }


            } else {

                // âž• Nuevo producto
                val subtotal = PedidoCalculos.calcularSubtotal(precio, descuentoMax, cantidad)
                val impuestosDb = pedidoRepository.getImpuestosParaProducto(producto.id_producto)
                val impuestosCalculados = impuestosDb.mapNotNull { impuesto ->
                    if (impuesto.tipo_factor == "Tasa") {
                        val tasa = impuesto.tasa_o_cuota ?: 0.0
                        val monto = PedidoCalculos.calcularImpuesto(subtotal, tasa)
                        ImpuestoCalculado(
                            idImpuesto = impuesto.id_impuesto,
                            nombre = impuesto.nombre ?: "Impuesto",
                            tipoAplicacion = impuesto.tipo_aplicacion,
                            tipoFactor = impuesto.tipo_factor,
                            tasa = tasa,
                            base = subtotal,
                            monto = monto,
                            cImpuesto = impuesto.c_impuesto,
                            signo = impuesto.signo
                        )
                    } else null
                }
                val totalImpuestos = PedidoCalculos.sumarImportes(impuestosCalculados.map { it.monto })
                val total = PedidoCalculos.calcularTotal(subtotal, impuestosCalculados.map { it.monto })

                val nuevaLinea = PedidoLinea(
                    producto = producto,
                    cantidad = cantidad,
                    precio = precio,
                    descuento = descuentoMax,
                    subtotal = subtotal,
                    impuestos = impuestosCalculados,
                    total = total,
                    presentacionId = pedidoRepository.getPresentacionVentaParaProducto(producto.id_producto)?.id_presentacion,
                    almacenId = 1
                )
                lineasActuales.add(nuevaLinea)
                val nuevosTotales = calcularTotalesDetallados(lineasActuales)
                // Emitir evento de éxito
                _state.update {
                    val mantenerTopProductos = it.searchQuery == "Top Productos"
                    it.copy(
                        lineasPedido = lineasActuales,
                        productoError = null,
                        productosEncontrados = if (mantenerTopProductos) it.productosEncontrados else emptyList(),
                        totalPedido = nuevosTotales.totalNeto, // <-- ACTUALIZADO
                        subtotalBruto = nuevosTotales.subtotalBruto, // <-- ACTUALIZADO
                        totalDescuento = nuevosTotales.totalDescuento, // <-- ACTUALIZADO
                        totalImpuestos = nuevosTotales.totalImpuestos, // <-- ACTUALIZADO
                        productosSustitutos = emptyList(),
                        eventoUI = PedidoUIEvent.ProductoAgregadoExitosamente
                    )
                }
            }
        }
    }

    fun consumirEventoUI() {
        _state.update { it.copy(eventoUI = null) }
    }


    fun aumentarCantidadProducto(productoId: Long, cantidad: Double, alerta: Boolean) {
        val lineas = _state.value.lineasPedido.toMutableList()
        val index = lineas.indexOfFirst { it.producto.id_producto == productoId }
        if (index < 0) return

        val linea = lineas[index]
        onAddProducto(linea.producto, cantidad, alerta) // Reutilizamos la lÃ³gica de onAddProducto
    }

    fun disminuirCantidadProducto(productoId: Long) {
        val lineas = _state.value.lineasPedido.toMutableList()
        val index = lineas.indexOfFirst { it.producto.id_producto == productoId }
        if (index < 0) return

        val linea = lineas[index]

        // DETERMINAR EL DECREMENTO
        // Si es Truper, disminuimos el 'inner', si no, disminuimos 1
        val esTruper = linea.producto.marcaNombre?.lowercase() == "truper"
        val decremento = if (esTruper && (linea.producto.inner_pack ?: 0) > 0) {
            linea.producto.inner_pack!!.toDouble()
        } else {
            1.0
        }

        val nuevaCantidad = linea.cantidad - decremento

        if (nuevaCantidad <= 0) {
            // ðŸ”¹ Eliminar producto si llega a 0 o menos
            lineas.removeAt(index)
        } else {
            // ðŸ”¹ Recalcular lÃ­nea con la nueva cantidad (múltiplo preservado)
            val nuevoSubtotal = PedidoCalculos.calcularSubtotal(linea.precio, linea.descuento, nuevaCantidad)
            val nuevosImpuestos = linea.impuestos.map {
                it.copy(
                    base = nuevoSubtotal,
                    monto = PedidoCalculos.calcularImpuesto(nuevoSubtotal, it.tasa)
                )
            }
            val total = PedidoCalculos.calcularTotal(nuevoSubtotal, nuevosImpuestos.map { it.monto })

            lineas[index] = linea.copy(
                cantidad = nuevaCantidad,
                subtotal = nuevoSubtotal,
                impuestos = nuevosImpuestos,
                total = total
            )
        }

        // ðŸ”¹ Actualizar estado global con los totales recalculados
        val nuevosTotales = calcularTotalesDetallados(lineas)
        _state.update {
            it.copy(
                lineasPedido = lineas,
                totalPedido = nuevosTotales.totalNeto,
                subtotalBruto = nuevosTotales.subtotalBruto,
                totalDescuento = nuevosTotales.totalDescuento,
                totalImpuestos = nuevosTotales.totalImpuestos,
            )
        }
    }

    fun disminuirCantidadProducto2(productoId: Long) {
        val lineas = _state.value.lineasPedido.toMutableList()
        val index = lineas.indexOfFirst { it.producto.id_producto == productoId }
        if (index < 0) return

        val linea = lineas[index]
        val nuevaCantidad = linea.cantidad - 1
        if (nuevaCantidad <= 0) {
            // ðŸ”¹ Eliminar producto si llega a 0
            lineas.removeAt(index)

            val nuevosTotales = calcularTotalesDetallados(lineas)

            _state.update {
                it.copy(
                    lineasPedido = lineas,
                    totalPedido = nuevosTotales.totalNeto, // Actualizar total neto
                    subtotalBruto = nuevosTotales.subtotalBruto, // <-- NUEVO
                    totalDescuento = nuevosTotales.totalDescuento, // <-- NUEVO
                    totalImpuestos = nuevosTotales.totalImpuestos,
                )
            }
        } else {
            // ðŸ”¹ Recalcular totales
            val nuevoSubtotal = PedidoCalculos.calcularSubtotal(linea.precio, linea.descuento, nuevaCantidad)
            val nuevosImpuestos = linea.impuestos.map {
                it.copy(
                    base = nuevoSubtotal,
                    monto = PedidoCalculos.calcularImpuesto(nuevoSubtotal, it.tasa)
                )
            }
            val total = PedidoCalculos.calcularTotal(nuevoSubtotal, nuevosImpuestos.map { it.monto })

            lineas[index] = linea.copy(
                cantidad = nuevaCantidad,
                subtotal = nuevoSubtotal,
                impuestos = nuevosImpuestos,
                total = total
            )

            val nuevosTotales = calcularTotalesDetallados(lineas)

            _state.update {
                it.copy(
                    lineasPedido = lineas,
                    totalPedido = nuevosTotales.totalNeto, // Actualizar total neto
                    subtotalBruto = nuevosTotales.subtotalBruto, // <-- NUEVO
                    totalDescuento = nuevosTotales.totalDescuento, // <-- NUEVO
                    totalImpuestos = nuevosTotales.totalImpuestos,
                )
            }
        }
    }


    /*  fun onAddProducto(producto: SearchProductosConDetalle, cantidad: Double) {
          val cliente = _state.value.clienteSeleccionado ?: return

          if (producto.activo == 0L) {
              _state.update { it.copy(productoError = "Producto descontinuado.") }
              return
          }

          viewModelScope.launch {
              // LÃ³gica de precios, impuestos, etc. (Sin cambios)
              val precioInfo = pedidoRepository.getPrecioParaCliente(producto.id_producto, cliente.numero_precio_id)
              val precio = precioInfo?.precio ?: producto.costo_ultimo ?: 0.0
              val descuento = producto.descuento_max ?: 0.0
              val precioConDescuento = precio * (1 - (descuento / 100))
              val subtotal = precioConDescuento * cantidad
              val impuestosDb = pedidoRepository.getImpuestosParaProducto(producto.id_producto)
              val impuestosCalculados = mutableListOf<ImpuestoCalculado>()
              impuestosDb.forEach { impuesto ->
                  if (impuesto.tipo_factor == "Tasa") {
                      val tasa = impuesto.tasa_o_cuota ?: 0.0
                      val montoImpuesto = subtotal * (tasa / 100)
                      impuestosCalculados.add(
                          ImpuestoCalculado(
                              nombre = impuesto.nombre ?: "Impuesto",
                              tasa = tasa,
                              monto = montoImpuesto
                          )
                      )
                  }
              }
              val totalImpuestos = impuestosCalculados.sumOf { it.monto }
              val total = subtotal + totalImpuestos
              val nuevaLinea = PedidoLinea(
                  producto = producto,
                  cantidad = cantidad,
                  precio = precio,
                  descuento = descuento,
                  subtotal = subtotal,
                  impuestos = impuestosCalculados,
                  total = total,
                  presentacionId = producto.id_unidad,
                  almacenId = 1
              )

              _state.update {
                  it.copy(
                      lineasPedido = it.lineasPedido + nuevaLinea,
                      productoError = null,
                      productosSustitutos = emptyList()
                  )
              }
          }
      }
  */
    // --- LÃ“GICA DE BOTONES ACTUALIZADA ---

    /**
     * Llamado desde el botón en Step 3 (Productos).
     * Simplemente navega al paso de Resumen.
     */

    fun updateObservacion(observacion: String) {
        _state.update { it.copy(observacion = observacion) }
    }

    fun onRevisarPedido() {
        _state.update {
            it.copy(currentStep = PedidoStep.RESUMEN)

        }
    }


    /**
     * Llamado desde el botón en Step 4 (Resumen).
     * Realiza el guardado en la base de datos.
     */
    fun onConfirmarYSGuardar() {
        val s = _state.value
        // Validaciones
        if (s.agenteLogueado == null || s.usuarioLogueado == null || s.serieSeleccionada == null || s.folioAsignado == null || s.clienteSeleccionado == null || s.lineasPedido.isEmpty()) {
            _state.update { it.copy(error = "Faltan datos (Serie, Cliente o Productos)") }
            return
        }

        val idDireccion = s.direccionSeleccionada?.id

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val plazoParaGuardar: Long = s.plazoSeleccionado
            val success = pedidoRepository.guardarPedidoLocal(
                agente = s.agenteLogueado,
                usuario = s.usuarioLogueado,
                cliente = s.clienteSeleccionado,
                serie = s.serieSeleccionada!!, // Ya validado
                folio = s.folioAsignado,
                lineas = s.lineasPedido,
                plazo = plazoParaGuardar,
                observaciones = s.observacion,
                tipoDocumento = s.tipoDocumento,
                ubicacion_id = idDireccion

            )

            if (success != null) {
                // Navega a la pantalla final de éxito

                _state.update {
                    it.copy(
                        isLoading = false,
                        currentStep = PedidoStep.EXITO,
                        pedidoCreadoId = success
                    )
                }
                val now = LocalDateTime.now()
                val formatter =
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME // Formato: YYYY-MM-DDTHH:MM:SS.mmm
                val updatedAtString = now.format(formatter)
                pedidoRepository.savePedidoFolioSerie(
                    s.serieSeleccionada.id_serie,
                    s.folioAsignado,
                    updatedAtString
                )
            } else {
                _state.update { it.copy(isLoading = false, error = "No se pudo guardar el pedido") }
            }
        }
    }

    // --- Funciones de búsqueda (Sin cambios) ---

    fun searchProducto(query: String) {
        val requestId = ++productSearchSequence
        productSearchJob?.cancel()
        _state.update {
            it.copy(
                searchQuery = query,
                productoError = null,
                productosSustitutos = emptyList()
            )
        }
        productSearchJob = viewModelScope.launch {
            if (query != "Top Productos") {
                delay(180)
            }
            val cliente = _state.value.clienteSeleccionado
            if (cliente == null) {
                _state.update { it.copy(error = "Primero selecciona un cliente antes de buscar productos.") }
                return@launch
            }
            if (query.isNullOrBlank()|| query.isNullOrEmpty()) {
                _state.update {
                    it.copy(
                        productosEncontrados = emptyList(),
                        productoError = null,
                        productosSustitutos = emptyList()
                    )
                }
                return@launch
            }


//            val productos = if (query.isBlank()) {
//                pedidoRepository.getTop20ProductosPorCliente(cliente.id_cliente)
//            } else {
//                pedidoRepository.searchProductosConDetalle(query)
//            }

            val productos = withContext(Dispatchers.Default) {
                if (query == "Top Productos") {
                    pedidoRepository.getTop20ProductosPorCliente(cliente.id_cliente)
                } else {
                    pedidoRepository.searchProductosConDetalle(query)
                }
            }

            _state.update {
                if (requestId != productSearchSequence || it.searchQuery != query) return@update it
                it.copy(
                    productosEncontrados = productos,
                    productoError = null,
                    productosSustitutos = emptyList()
                )
            }
        }
    }


    private fun calcularTotalesDetallados(lineas: List<PedidoLinea>): TotalesPedido {
        var subtotalBruto = 0.0
        var totalDescuento = 0.0
        var totalImpuestos = 0.0

        lineas.forEach { linea ->
            // Subtotal Bruto (Costo base * Cantidad)
            val precioBrutoLinea = PedidoCalculos.redondearImporte(linea.cantidad * linea.precio)
            subtotalBruto += precioBrutoLinea

            // Descuento total de la lÃ­nea
            val montoDescuentoTotalLinea = PedidoCalculos.calcularImporteDescuento(
                linea.precio,
                linea.descuento,
                linea.cantidad
            )
            totalDescuento += montoDescuentoTotalLinea

            // Total de Impuestos de la lÃ­nea (ya calculado en PedidoLinea)
            totalImpuestos += PedidoCalculos.sumarImportes(linea.impuestos.map { it.monto })
        }

        // El total neto debe ser: Subtotal Bruto - Total Descuento + Total Impuestos
        subtotalBruto = PedidoCalculos.redondearImporte(subtotalBruto)
        totalDescuento = PedidoCalculos.redondearImporte(totalDescuento)
        totalImpuestos = PedidoCalculos.redondearImporte(totalImpuestos)
        val totalNeto = PedidoCalculos.redondearImporte(subtotalBruto - totalDescuento + totalImpuestos)

        return TotalesPedido(
            subtotalBruto = subtotalBruto,
            totalDescuento = totalDescuento,
            totalImpuestos = totalImpuestos,
            totalNeto = totalNeto
        )
    }
    /* fun searchProducto(query: String) {
        viewModelScope.launch {
            val productos = pedidoRepository.searchProductosConDetalle(query)
            _state.update { it.copy(
                productosEncontrados = productos,
                productoError = null,
                productosSustitutos = emptyList()
            )}
        }
    }*/

//    fun searchCliente(query: String) {
//        viewModelScope.launch {
//            val clientes = pedidoRepository.searchClientes(query)
//            _state.update { it.copy(clientesEncontrados = clientes) }
//        }
//    }

    // En NuevoPedidoViewModel.kt

    fun searchCliente(query: String) {
        viewModelScope.launch {
            val agente = _state.value.agenteLogueado
            if (agente == null) {
                _state.update { it.copy(error = "No se encontrÃ³ agente logueado") }
                return@launch
            }

            val clientes = if (query.isBlank()) { // <--- ESTA ES LA CLAVE
                // 1. Si la búsqueda está vacía o en blanco, carga los clientes del agente.
                pedidoRepository.getClientesActivosPorAgente(agente.id_agente)
            } else {
                // 2. Si hay texto, busca en todos los clientes.
                pedidoRepository.searchClientes(query)
            }

            _state.update { it.copy(clientesEncontrados = clientes, error = null) }
        }
    }
    fun setTipoDocumento(tipo: TipoDocumento) {
        _state.update { it.copy(tipoDocumento = tipo) }
    }


    fun reiniciarFlujo() {
        val usuarioActual = _state.value.usuarioLogueado
        val agenteActual = _state.value.agenteLogueado
        val seriesActuales = _state.value.seriesDisponibles

        _state.value = NuevoPedidoState(
            currentStep = PedidoStep.SERIE_FOLIO,
            usuarioLogueado = usuarioActual,
            agenteLogueado = agenteActual,
            seriesDisponibles = seriesActuales
        )
    }
}

