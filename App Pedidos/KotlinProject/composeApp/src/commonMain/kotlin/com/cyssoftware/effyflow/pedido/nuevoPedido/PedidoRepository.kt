package com.cyssoftware.effyflow.pedido.nuevoPedido

import com.benasher44.uuid.uuid4
import com.cyssoftware.effyflow.db.Agentes
import com.cyssoftware.effyflow.db.Clientes
import com.cyssoftware.effyflow.db.DatabaseProvider // Importa tu provider
import com.cyssoftware.effyflow.db.GetImpuestosParaProducto
import com.cyssoftware.effyflow.db.Precios_producto
import com.cyssoftware.effyflow.db.Presentaciones_producto
import com.cyssoftware.effyflow.db.Productos
import com.cyssoftware.effyflow.db.SearchProductosConDetalle
import com.cyssoftware.effyflow.db.SearchProductosConDetalleStock
import com.cyssoftware.effyflow.db.Series_folios
import com.cyssoftware.effyflow.db.Users
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.ComponenteKit
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.PedidoLinea
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.TipoDocumento
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.TipoPedido
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class PedidoRepository {

    private val db= DatabaseProvider.database

    private val pedidosQueries = db.pedidosQueries
    private val detallePedidosQueries = db.detalle_pedidosQueries
    private val clientesQueries = db.clientesQueries
    private val productosQueries = db.productosQueries
    private val agentesQueries = db.agentesQueries
    private val usersQueries = db.usersQueries
    private val seriesFoliosUsuariosQueries = db.series_folios_usuariosQueries
    private val seriesFoliosQueries = db.series_foliosQueries
    private val preciosProductoQueries = db.precios_productoQueries
    private val stockProductosQueries = db.stock_productosQueries
    private val productosSustitutosQueries = db.productos_sustitutosQueries
    private val productosImpuestosQueries =db.productos_impuestosQueries
    private val presentacionesProductoQueries = db.presentaciones_productoQueries
    private val ubicacionesQueries = db.clientes_ubicacionesQueries
    private val productosKitsQueries = db.productos_kitsQueries

    // PedidoRepository.kt
    fun getComponentesDelKit(idKit: Long): List<ComponenteKit> {
        val lineasKit = db.productos_kitsQueries.getComponentesByKit(idKit).executeAsList()

        return lineasKit.mapNotNull { linea ->
            // CAMBIO AQUÍ: Usar getProductoDetalladoStockById
            val detalle = db.productosQueries.getProductoDetalladoStockById(linea.id_producto_componente)
                .executeAsOneOrNull()

            if (detalle != null) {
                ComponenteKit(
                    producto = detalle, // Ahora el tipo coincidirá perfectamente
                    cantidad = linea.cantidad
                )
            } else null
        }
    }
    // --- Funciones de carga (Usadas por el VM) ---

    fun getUsuario(userId: Long): Users? {
        return usersQueries.findById(userId).executeAsOneOrNull()
    }

    fun getAgentePorUsuario(userId: Long): Agentes? {
        return agentesQueries.findByUsuarioId(userId).executeAsOneOrNull()
    }

    fun getSeriesPorUsuario(usuarioId: Long): List<Series_folios> {
        return seriesFoliosUsuariosQueries.selectSeriesPorUsuario(usuarioId).executeAsList()
    }


    suspend fun getSiguienteFolio(serieId: Long): Long {
        val maxFolio = db.folioSeries_pedidosQueries.getMaxFolioPorSerie(serieId).executeAsOneOrNull()?.max_folio
        val folioSincronizado = seriesFoliosQueries.getSerieByIdFromPedido(serieId).executeAsOneOrNull()?.folio
        return PedidoCalculos.calcularSiguienteFolio(folioSincronizado, maxFolio)
    }

    suspend fun existePedidoConFolio(serieId: Long, folio: Long): Boolean {
        val count = db.folioSeries_pedidosQueries.existePedidoConFolio(serieId, folio).executeAsOne()
        return count > 0
    }

    suspend fun savePedidoFolioSerie(serieId: Long, folio: Long, updatedAt: String) {
        // Nota: Usamos 'db.folioSeries_pedidosQueries' asumiendo que es el nombre generado por SQLDelight.
        // Si tu instancia de base de datos se llama diferente, ajusta la referencia.
        try {
            db.folioSeries_pedidosQueries.upsertPedidos(
                serie_id = serieId,
                folio = folio,
                updated_at = updatedAt
            )

        } catch (e: Exception) {
            // Manejo de error si la base de datos falla al guardar
            e.printStackTrace()
        }
    }


    fun searchClientes(query: String): List<Clientes> {
        return clientesQueries.search(query, query).executeAsList()
    }

    suspend fun getClientesActivosPorAgente(agenteId: Long): List<Clientes> {
        return db.clientesQueries.getAllActivosByAgente(agenteId).executeAsList()
    }

    suspend fun searchClientesPorAgente(agenteId: Long, query: String): List<Clientes> {
        return db.clientesQueries.searchByAgente(agenteId, query, query).executeAsList()
    }

    suspend fun getTop20ProductosPorCliente(clienteId: Long): List<SearchProductosConDetalleStock> {
        return db.productosQueries.getTop20ByClienteComplete(clienteId)
            .executeAsList()
            .map {
                SearchProductosConDetalleStock(
                    id_producto = it.id_producto,
                    codigo = it.codigo,
                    nombre = it.nombre,
                    descripcion = it.descripcion,
                    activo = it.activo,
                    id_categoria = it.id_categoria,
                    upc = it.upc,
                    largo = it.largo,
                    ancho = it.ancho,
                    alto = it.alto,
                    grueso = it.grueso,
                    peso = it.peso,
                    tipo = it.tipo,
                    clave_sat = it.clave_sat,
                    id_unidad = it.id_unidad,
                    costo_ultimo = it.costo_ultimo,
                    costo_reposicion = it.costo_reposicion,
                    costo_anterior = it.costo_anterior,
                    costo_promedio = it.costo_promedio,
                    descuento_max = it.descuento_max,
                    tipo_articulo = it.tipo_articulo,
                    id_marca = it.id_marca,
                    id_medida = it.id_medida,
                    updated_at = it.updated_at,
                    marcaNombre = it.marcaNombre,
                    unidadNombre = it.unidadNombre,
                    existenciaTotal = it.existenciaTotal,
                    categoriaNombre = it.categoriaNombre,
                    precioUnitario = it.precioUnitario,
                    impuestosNombres = it.impuestosNombres,
                    permite_negativo = it.permite_negativo,
                    inner_pack = it.inner_pack,
                    master_pack = it.master_pack,
                )
            }
    }




    // --- ACTUALIZADO: Llama a la query desde 'productosQueries' ---
    fun searchProductosConDetalle(query: String): List<SearchProductosConDetalleStock> {
        return productosQueries.searchProductosConDetalleStock(query, query).executeAsList()
    }

    fun getPrecioParaCliente(productoId: Long, tipoPrecioId: Long?): Precios_producto? {
        if (tipoPrecioId == null) return null
        return preciosProductoQueries.getPrecio(productoId, tipoPrecioId).executeAsOneOrNull()
    }

    fun getStock(productoId: Long): Double {
        return stockProductosQueries.findByProductoId(productoId).executeAsOneOrNull()?.SUM ?: 0.0
    }

    fun getProductosSustitutos(productoId: Long): List<Productos> {
        return productosSustitutosQueries.findByProductoId(productoId).executeAsList()
    }

    // --- ACTUALIZADO: Llama a la query desde 'productosImpuestosQueries' ---
    fun getImpuestosParaProducto(productoId: Long): List<GetImpuestosParaProducto> {
        return productosImpuestosQueries.getImpuestosParaProducto(productoId).executeAsList()
    }

    fun getPresentacionVentaParaProducto(productoId: Long): Presentaciones_producto? {
        return presentacionesProductoQueries.getPresentacionVentaPorProducto(productoId).executeAsOneOrNull()
    }

    // --- Lógica de Guardado (CU-06) ---
    fun guardarPedidoLocal(
        agente: Agentes,
        usuario: Users,
        cliente: Clientes,
        serie: Series_folios,
        folio: Long,
        lineas: List<PedidoLinea>, // Clase definida en el ViewModel
        plazo: Long,
        observaciones: String,
        tipoDocumento: TipoDocumento,
        ubicacion_id: Long?
    ): Long? {

        val uuidLocal = uuid4().toString()
        val fechaActual = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).toString()
        val totalPedido = lineas.sumOf { it.total }
        var nuevoPedidoId: Long? = null
        try {
            db.transaction {
                // 1. Insertar Encabezado

                val idUser: Long = agente.id_usuario ?: agente.id_agente
                val idFinalUbicacion = if (ubicacion_id == -1L) null else ubicacion_id
                pedidosQueries.insertPedidos(
                    cliente_id = cliente.id_cliente,
                    agente_ventas_id = agente.id_agente,
                    usuario_creo_id = idUser,
                    usuario_autorizo_id = null,
                    serie_id = serie.id_serie,
                    folio = folio,
                    fecha_creacion = fechaActual,
                    fecha_autorizacion = null,
                    fecha_envio_almacen = null,
                    fecha_cierre = null,
                    plazo = plazo ?: 0,
                    estado = "PENDIENTE_SYNC",
                    observaciones = observaciones,
                    venta_id = null,
                    total_solicitado = totalPedido,
                    total_autorizado = 0.0,
                    total_facturado = 0.0,
                    uuid_local = uuidLocal,
                    uuid_server = null,
                    updated_at = fechaActual,
                    synced_at = "PENDIENTE_SYNC",
                    tipo_documento = tipoDocumento.name,
                    ubicacion_id = idFinalUbicacion
                )
                println("""
    --- GUARDANDO NUEVO PEDIDO LOCAL ---
    Cliente ID: ${cliente.id_cliente}
    Agente ID: ${agente.id_agente}
    Usuario: $idUser
    Serie ID: ${serie.id_serie}
    Folio: $folio
    Plazo: $plazo
    Ubicación ID: $idFinalUbicacion
    Total: $totalPedido
    UUID Local: $uuidLocal
    Tipo Doc: ${tipoDocumento.name}
    ------------------------------------
""".trimIndent())


                nuevoPedidoId = pedidosQueries.lastInsertRowId().executeAsOne()
                // 2. Insertar Detalles
                lineas.forEach { linea ->

                    val impuestosString = RenglonFiscalSnapshot.serializar(linea)

                    detallePedidosQueries.insertDetallePedidos(
                        pedido_id = nuevoPedidoId,
                        producto_id = linea.producto.id_producto, // ID del producto
                        presentacion_id = linea.presentacionId,
                        almacen_id = linea.almacenId,
                        cantidad_solicitada = linea.cantidad,
                        cantidad_autorizada = 0.0,
                        cantidad_reservada = 0.0,
                        cantidad_surtida = 0.0,
                        cantidad_cancelada = 0.0,
                        precio_unitario = linea.precio,
                        descuento = linea.descuento,
                        impuestos = impuestosString, // Columna de texto
                        total_linea = linea.total,
                        estado_linea = "PENDIENTE",
                        updated_at = fechaActual
                    )
                }

                // 3. Actualizar el folio consecutivo
               /* seriesFoliosQueries.updateSeriesFolios(
                    id_serie = serie.id_serie,
                    codigo_serie = serie.codigo_serie,
                    descripcion = serie.descripcion,
                    tipo_movimiento_id = serie.tipo_movimiento_id,
                    tipo_movimiento_inventario = serie.tipo_movimiento_inventario,
                    folio = folio, // <-- Actualizamos el folio usado
                    formato_impresion = serie.formato_impresion,
                    serie_devolucion_id = serie.serie_devolucion_id,
                    activa = serie.activa,
                    tipo_serie = serie.tipo_serie,
                    updated_at = fechaActual
                )*/
            }
            return nuevoPedidoId // Éxito
        } catch (e: Exception) {
            println("Error al guardar pedido: ${e.message}")
            return null // Falla
        }
    }


    // En PedidoRepository.kt
    fun getDireccionesDelCliente(idCliente: Long): List<DireccionCombo> {
        val cliente = clientesQueries.findById(idCliente).executeAsOne()
        val alternativas = ubicacionesQueries.getUbicacionesPorCliente(idCliente).executeAsList()

        val listaDirecciones = mutableListOf<DireccionCombo>()

        // 1. Agregar Dirección Principal (desde clientes.sq)
        listaDirecciones.add(
            DireccionCombo(
                id = -1, // ID ficticio para la principal
                alias = "Dirección Principal",
                calle = cliente.direccion ?: "",
                cp = cliente.codigo_postal ?: "",
                direcccion="${cliente.direccion ?: ""}, ${cliente.municipio ?: ""}, ${cliente.localidad ?: ""}, ${cliente.codigo_postal ?: ""}"
            )
        )

        // 2. Agregar Alternativas (desde clientes_ubicaciones.sq)
        alternativas.forEach { ub ->
            val direccionCompleta = listOf(
                ub.direccion,
                ub.no_exterior,
                ub.localidad,
                ub.colonia,
                ub.municipio,
                ub.codigo_postal
            ).filter { !it.isNullOrBlank() }
                .joinToString(", ")
            listaDirecciones.add(
                DireccionCombo(
                    id = ub.id_cliente_ubicacion,
                    alias = ub.alias ?: "Sin Alias",
                    calle = ub.direccion ?: "",
                    cp = ub.codigo_postal ?: "",
                    direcccion= direccionCompleta
                )
            )
        }
        return listaDirecciones
    }

    data class DireccionCombo(val id: Long, val alias: String, val calle: String, val cp: String,val direcccion: String)

}
