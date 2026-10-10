package com.cyssoftware.effyflow.pedido.sincronizacion.viewModel


import androidx.compose.runtime.*
import com.cyssoftware.effyflow.config.AppConfigRepository
import com.cyssoftware.effyflow.db.DatabaseProvider.database
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable


@Serializable
data class RefreshTokenResponse(
    val accessToken: String,
    val refreshToken: String,
)

class SyncViewModel(
    private val configRepository: AppConfigRepository = AppConfigRepository(),
    private val baseCatalogosUrl: String? = null,
    private val basePedidosUrl: String? = null,
    private val getToken: () -> Pair<String?, String?> = { Pair(null, null) },
    // 🔑 FUNCIÓN PARA GUARDAR: Guarda los nuevos tokens
    private val saveTokens: (String, String) -> Unit = { _, _ -> },
    private val onSyncComplete: () -> Unit = {},
) {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                isLenient = true
            })
        }
        // 🚀 PLUGIN DE AUTENTICACIÓN
        configureSyncBearer(getToken, saveTokens) {
            "${configRepository.requireBaseUrl()}/api/auth/refresh"
        }
    }

    private val pedidoEstadosSync by lazy {
        PedidoEstadosSync(database) { after, until ->
            val endpoint = (basePedidosUrl ?: "${configRepository.requireBaseUrl()}/api/sync/pedidos").trimEnd('/') + "/estados"
            cargarPedidoEstados(client, endpoint, after, until)
        }
    }
    companion object {
        private val syncMutex = kotlinx.coroutines.sync.Mutex()
    }

    var isLoading by mutableStateOf(false)
    var message by mutableStateOf("")
    var progress by mutableStateOf(0f)
    var currentCatalog by mutableStateOf("")

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    fun close() { scope.cancel(); client.close() }

    fun syncEstadosPedidos() {
        scope.launch {
            syncMutex.withLock {
                try {
                    isLoading = true
                    message = "Consultando estados ERP..."
                    pedidoEstadosSync.sincronizar()
                    message = "Estados ERP actualizados."
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    message = "No se pudieron actualizar los estados ERP. Se conserva el último estado conocido; verifica conexión y sesión de Sync."
                } finally { isLoading = false }
            }
        }
    }

    // 🔄 Lista de catálogos a sincronizar
    private val catalogs = listOf(
        "clientes", "productos", "precios_producto", "stock_productos",
        "series_folios", "series_folios_usuarios", "usuarios", "agentes",
        "unidades_medida", "categorias", "impuestos", "productos_impuestos",
        "presentaciones_producto", "productos_sustitutos", "marcas", "pedidos", "detalle_pedidos", "kits_detalle",
        "clientes_ubicaciones"

    )

    private fun getLastUpdatedAt(tableName: String): String? {
        // 💡 NOTA: Se asume que en el archivo .sq de cada tabla se ha definido
        // una query llamada `getMaxUpdatedAt` que devuelve un campo `max_updated_at`.
        return when (tableName) {
            "clientes" -> database.clientesQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "productos" -> database.productosQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "precios_producto" -> database.precios_productoQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "stock_productos" -> database.stock_productosQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "series_folios" -> database.series_foliosQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "series_folios_usuarios" -> database.series_folios_usuariosQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "usuarios" -> database.usersQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at // Asumiendo que el objeto generado es `usersQueries`
            "agentes" -> database.agentesQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "unidades_medida" -> database.unidades_medidaQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "categorias" -> database.categoriasQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "impuestos" -> database.impuestosQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "productos_impuestos" -> database.productos_impuestosQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "presentaciones_producto" -> database.presentaciones_productoQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "productos_sustitutos" -> database.productos_sustitutosQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "marcas" -> database.marcasQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            "pedidos" -> database.pedidosQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at
            /*"detalle_pedidos" -> database.detalle_pedidosQueries.getMaxUpdatedAt().executeAsOneOrNull()?.max_updated_at*/
            // Para cualquier otro catálogo no listado, retorna null (fuerza sincronización completa)

//            "kits_detalle"->database.productos_kitsQueries.getMaxUpdatedAt()
//                .executeAsOneOrNull()?.max_updated_at

            "clientes_ubicaciones" -> database.clientes_ubicacionesQueries.getMaxUpdatedAt()
                .executeAsOneOrNull()?.max_updated_at

            else -> null
        }
    }

    fun syncAllCatalogs() {
        scope.launch {
            syncMutex.withLock {
            try {
                isLoading = true
                message = "Iniciando sincronización..."
                progress = 0f

                pedidoEstadosSync.sincronizar()
                val totalCatalogs = catalogs.size

                for ((index, catalog) in catalogs.withIndex()) {
                    currentCatalog = catalog

                    val lastUpdated = getLastUpdatedAt(catalog)
                    message = "Sincronizando $catalog desde: ${lastUpdated ?: "Inicio"}..."
                    syncSingleCatalog(catalog, lastUpdated)


                    //syncSingleCatalog(catalog)
                    progress = (index + 1f) / totalCatalogs
                }

                message = "✅ Todos los catálogos sincronizados correctamente"
            } catch (e: Exception) {
                message = "❌ Error general: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
                onSyncComplete()
            }
            }
        }
    }

    private suspend fun syncSingleCatalog(nombre: String, updatedAfter: String?) {
        if (nombre == "usuarios") {
            com.cyssoftware.effyflow.login.data.authorizationMutex.withLock {
                val users = com.cyssoftware.effyflow.login.data.downloadAuthorizedUsers(client, configRepository.requireBaseUrl())
                com.cyssoftware.effyflow.login.data.AuthLocalDataSource(database).applyCatalog(users)
            }
            return
        }
        var page = 0
        var totalPages = 1


        var latestSyncDate: String? = null
        latestSyncDate = updatedAfter
        if (latestSyncDate != null) {
            println("▶️ $nombre: Iniciando sync incremental desde $latestSyncDate")
        }

        if (nombre == "kits_detalle") {
            database.productos_kitsQueries.deleteAllProductosKits()
            println("🗑️ Tabla productos_kits limpiada antes de iniciar la descarga.")
        }


        do {
            val catalogUrl = baseCatalogosUrl ?: "${configRepository.requireBaseUrl()}/api/sync/catalogos"
            val response: JsonObject = client.get("$catalogUrl/$nombre") {
                parameter("page", page)
                parameter("size", 500)
                contentType(ContentType.Application.Json)
                if (latestSyncDate != null) {
                    parameter("updatedAfter", latestSyncDate)
                }
            }.body()


            val data = response["data"]
            val pageNumber = response["page"]?.jsonPrimitive?.int ?: 0
            totalPages = response["totalPages"]?.jsonPrimitive?.int ?: 1


            val dataSice = data as? JsonArray
            val recordsCount = dataSice?.size ?: 0

            println("📦 Catálogo: $nombre, página $pageNumber de $totalPages")
            println("📄 Registros obtenidos: $recordsCount")

            // TODO: aquí deberías guardar los datos en SQLite (SQLDelight)
            try {
                when (nombre) {
                    "agentes" -> saveAgentesData(data as JsonArray)
                    "categorias" -> saveCategoriasData(data as JsonArray)
                    "clientes" -> saveClientesData(data as JsonArray)
                    "impuestos" -> saveImpuestosData(data as JsonArray)
                    "marcas" -> saveMarcasData(data as JsonArray)
                    "precios_producto" -> savePreciosProductoData(data as JsonArray)
                    "productos" -> saveProductosData(data as JsonArray)
                    "productos_impuestos" -> saveProductosImpuestosData(data as JsonArray)
                    "presentaciones_producto" -> savePresentacionesProductoData(data as JsonArray)
                    "productos_sustitutos" -> saveProductosSustitutosData(data as JsonArray)
                    "series_folios" -> saveSeriesFoliosData(data as JsonArray)
                    "series_folios_usuarios" -> saveSeriesFoliosUsuariosData(data as JsonArray)
                    "stock_productos" -> saveStockProductosData(data as JsonArray)
                    "unidades_medida" -> saveUnidadesMedidaData(data as JsonArray)
                    "pedidos" -> savePedidosData(data as JsonArray)
                    // Dentro de syncSingleCatalog en el bloque try-when
                    "kits_detalle" -> saveProductosKitsData(data as JsonArray)
                    "clientes_ubicaciones" -> saveClientesUbicacionesData(data as JsonArray)
                    else -> println("⚠️ No hay método de guardado definido para: $nombre")
                }
            } catch (e: Exception) {
                println("❌ Error al guardar datos de $nombre: ${e.message}")
                e.printStackTrace()
                // Aquí podrías decidir si continuar con la siguiente página o abortar
            }

            page++
        } while (page < totalPages)
    }

    private fun saveAgentesData(data: JsonArray) {
        // Accede a las queries específicas desde la instancia de la base de datos
        val agentesQueries = database.agentesQueries

        // 🚀 MEJORA: Envuelto en una transacción para inserciones masivas más rápidas
        agentesQueries.transaction {
            // 4. Itera sobre los registros y mapea el JSON a los tipos de SQLDelight
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    // ⚠️ CORRECCIÓN DE CAPITALIZACIÓN (CAMELCASE): idAgente
                    val agenteId = item.getLong("idAgente")

                    // Recomendación de validación (opcional, pero buena práctica)
                    if (agenteId == null || agenteId <= 0) {
                        println("❌ Error: idAgente es nulo o inválido, omitiendo registro: $item")
                        return@forEach // Salta al siguiente elemento
                    }

                    // 5. Llama a la función 'insert' generada por SQLDelight
                    agentesQueries.insertAgentes(
                        // ⚠️ CORRECCIÓN: Usar el valor 'idAgente' del JSON validado
                        id_agente = agenteId,
                        // ⚠️ CORRECCIÓN DE CAPITALIZACIÓN (CAMELCASE): nombre
                        nombre = item.getString("nombre"),
                        // ⚠️ CORRECCIÓN DE CAPITALIZACIÓN (CAMELCASE): idUsuario
                        id_usuario = item.getLong("idUsuario"),
                        // ⚠️ CORRECCIÓN DE CAPITALIZACIÓN (CAMELCASE): tipoAgenteId
                        tipo_agente_id = item.getLong("tipoAgenteId"),
                        // ⚠️ CORRECCIÓN DE CAPITALIZACIÓN (CAMELCASE): updatedAt
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    // Captura errores por si un registro falla (ej. violación de PRIMARY KEY)
                    println("❌ Error al insertar agente: ${e.message}")
                    println("Registro problemático: $item")
                }
            }
        }
        println("✅ Datos guardados para: agentes")
    }


    private fun saveCategoriasData(data: JsonArray) {
        val categoriasQueries = database.categoriasQueries

        // Envuelve en una transacción para inserciones masivas rápidas
        categoriasQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    categoriasQueries.insertCategorias(
                        id_categoria = item.getLong("idCategoria") ?: 0L,
                        nombre_categoria = item.getString("nombreCategoria"),
                        id_padre = item.getLong("idPadre"),
                        nivel = item.getLong("nivel") ?: 1L,
                        activo = item.getBooleanAsLong("activo"),
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar categoria: ${e.message}")
                    println("Registro problemático: $item")
                    // Aquí también podrías implementar 'updateCategorias' o un 'upsert'
                }
            }
        }
        println("✅ Datos guardados para: categorias")
    }


    private fun saveClientesData(data: JsonArray) {
        val clientesQueries = database.clientesQueries

        // Envuelve en una transacción para inserciones masivas rápidas
        clientesQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    // Asumiré que el JSON usa 'tipo_cliente_id' aunque el .sq diga 'tipo_clente_id'
                    // Si el JSON también tiene el error, cambia "tipo_cliente_id" por "tipo_clente_id"
                    clientesQueries.insertClientes(
                        id_cliente = item.getLong("idCliente") ?: 0L,
                        nombre = item.getString("nombre") ?: "",
                        rfc = item.getString("rfc"),
                        razon_social = item.getString("razonSocial"),
                        email = item.getString("email"),
                        telefono = item.getString("telefono"),
                        direccion = item.getString("direccion"),
                        codigo_postal = item.getString("codigoPostal"),
                        localidad = item.getString("localidad"),
                        municipio = item.getString("municipio"),
                        estado = item.getString("estado"),
                        pais = item.getString("pais"),
                        uso_cfdi = item.getString("usoCfdi"),
                        metodo_pago_sat = item.getString("metodoPagoSat"),
                        forma_pago_sat = item.getLong("formaPagoSat"),
                        activo = item.getBooleanAsLong("activo"),
                        numero_precio_id = item.getLong("numeroPrecioId"),
                        limite_credito = item.getLong("limiteCredito"),
                        credito_bloqueado = item.getBooleanAsLong("creditoBloqueado"),
                        motivo_bloqueo_credito = item.getString("motivoBloqueoCredito"),
                        fecha_bloqueo_credito = item.getString("fechaBloqueoCredito"),
                        agente_id = item.getLong("agenteId"),
                        tipo_clente_id = item.getLong("tipoClenteId"), // mantén igual si tu tabla usa el error tipográfico
                        ruta_id = item.getLong("rutaId"),
                        plazo = item.getLong("plazo"),
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar cliente: ${e.message}")
                    println("Registro problemático: $item")
                    // Implementar 'updateClientes' o 'upsert'
                }
            }
        }
        println("✅ Datos guardados para: clientes")
    }

    private fun saveImpuestosData(data: JsonArray) {
        val impuestosQueries = database.impuestosQueries

        impuestosQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    impuestosQueries.insertImpuestos(
                        id_impuesto = item.getLong("idImpuesto") ?: 0L,
                        nombre = item.getString("nombre"),
                        tipo_aplicacion = item.getString("tipoAplicacion"),
                        tipo_factor = item.getString("tipoFactor"),
                        tasa_o_cuota = item.getDouble("tasaOCuota"),
                        unidad_cuota = item.getString("unidadCuota"),
                        c_impuesto = item.getString("cimpuesto"),
                        nombre_sat = item.getString("nombreSat"),
                        signo = item.getLong("signo"),
                        activo = item.getBooleanAsLong("activo"),
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar impuesto: ${e.message}")
                    println("Registro problemático: $item")
                    // Implementar 'updateImpuestos' o 'upsert'
                }
            }
        }
        println("✅ Datos guardados para: impuestos")
    }

    private fun savePreciosProductoData(data: JsonArray) {
        val preciosQueries = database.precios_productoQueries

        preciosQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    // Clave primaria compuesta
                    preciosQueries.insertPreciosProducto(
                        producto_id = item.getLong("productoId") ?: 0L,
                        tipo_precio_id = item.getLong("tipoPrecioId") ?: 0L,
                        precio = item.getDouble("precio"),
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar precio_producto: ${e.message}")
                    println("Registro problemático: $item")
                    // Implementar 'updatePreciosProducto' o 'upsert'
                }
            }
        }
        println("✅ Datos guardados para: precios_producto")
    }

    private fun saveProductosData(data: JsonArray) {
        val productosQueries = database.productosQueries

        productosQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    productosQueries.insertProductos(
                        id_producto = item.getLong("idProducto") ?: 0L,
                        codigo = item.getString("codigo"),
                        nombre = item.getString("nombre"),
                        descripcion = item.getString("descripcion"),
                        activo = item.getBooleanAsLong("activo"),
                        id_categoria = item.getLong("idCategoria"),
                        upc = item.getString("upc"),
                        largo = item.getDouble("largo"),
                        ancho = item.getDouble("ancho"),
                        alto = item.getDouble("alto"),
                        grueso = item.getDouble("grueso"),
                        peso = item.getDouble("peso"),
                        tipo = item.getString("tipo"),
                        clave_sat = item.getString("claveSat"),
                        id_unidad = item.getLong("idUnidad"),
                        costo_ultimo = item.getDouble("costoUltimo"),
                        costo_reposicion = item.getDouble("costoReposicion"),
                        costo_anterior = item.getDouble("costoAnterior"),
                        costo_promedio = item.getDouble("costoPromedio"),
                        descuento_max = item.getDouble("descuentoMax"),
                        tipo_articulo = item.getString("tipoArticulo"),
                        id_marca = item.getLong("idMarca"),
                        id_medida = item.getLong("idMedida"),
                        permite_negativo = item.getBooleanAsLong("permiteNegativo"),
                        inner_pack = item.getLong("inner"),
                        master_pack = item.getLong("master"),
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar producto: ${e.message}")
                    println("Registro problemático: $item")
                    // Implementar 'updateProductos' o 'upsert'
                }
            }
        }
        println("✅ Datos guardados para: productos")
    }

    private fun saveProductosImpuestosData(data: JsonArray) {
        val prodImpQueries = database.productos_impuestosQueries

        prodImpQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    // Clave primaria compuesta
                    prodImpQueries.insertProductosImpuestos(
                        id_producto = item.getLong("idProducto") ?: 0L,
                        id_impuesto = item.getLong("idImpuesto") ?: 0L,
                        orden = item.getLong("orden") ?: 0L,
                        base_incluye_impuestos_previos = item.getBooleanAsLong("baseIncluyeImpuestosPrevios")
                            ?: 0L,
                        aplica_descuentos_en_base = item.getBooleanAsLong("aplicaDescuentosEnBase")
                            ?: 0L,
                        multiplicador_unidades = item.getDouble("multiplicadorUnidades") ?: 1.0,
                        updated_at = item.getString("updatedAt") ?: ""
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar producto_impuesto: ${e.message}")
                    println("Registro problemático: $item")
                    // Implementar 'updateProductosImpuestos' o 'upsert'
                }
            }
        }
        println("✅ Datos guardados para: productos_impuestos")
    }

    private fun savePresentacionesProductoData(data: JsonArray) {
        val presentacionesQueries = database.presentaciones_productoQueries

        presentacionesQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    presentacionesQueries.insertPresentacionesProducto(
                        id_presentacion = item.getLong("idPresentacion") ?: 0L,
                        id_producto = item.getLong("idProducto") ?: 0L,
                        id_unidad = item.getLong("idUnidad") ?: 0L,
                        descripcion = item.getString("descripcion"),
                        factor_conversion = item.getDouble("factorConversion"),
                        cantidad_base = item.getDouble("cantidadBase"),
                        es_unidad_venta = item.getBooleanAsLong("esUnidadVenta"),
                        es_predeterminada = item.getBooleanAsLong("esPredeterminada"),
                        updated_at = item.getString("updatedAt") ?: ""
                    )
                } catch (e: Exception) {
                    println("Error al insertar presentacion_producto: ${e.message}")
                    println("Registro problematico: $item")
                }
            }
        }
        println("Datos guardados para: presentaciones_producto")
    }

    private fun saveProductosSustitutosData(data: JsonArray) {
        val prodSusQueries = database.productos_sustitutosQueries

        prodSusQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    // id_producto_sustituto es AUTOINCREMENT, no lo enviamos
                    prodSusQueries.insertProductosSustitutos(
                        id_producto_sustituto = item.getLong("idProductoSustituto") ?: 0L,
                        producto_id = item.getLong("productoId") ?: 0L,
                        producto_sustituto_id = item.getLong("productoSustitutoId") ?: 0L,
                        prioridad = item.getLong("prioridad") ?: 1L,
                        motivo = item.getString("motivo") ?: "",
                        vigente = item.getBooleanAsLong("vigente"),
                        observaciones = item.getString("observaciones") ?: "",
                        updated_at = item.getString("updatedAt") ?: ""
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar producto_sustituto: ${e.message}")
                    println("Registro problemático: $item")
                }
            }
        }
        println("✅ Datos guardados para: productos_sustitutos")
    }

    private fun saveSeriesFoliosData(data: JsonArray) {
        val seriesQueries = database.series_foliosQueries

        seriesQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    seriesQueries.insertSeriesFolios(
                        id_serie = item.getLong("idSerie") ?: 0L,
                        codigo_serie = item.getString("codigoSerie"),
                        descripcion = item.getString("descripcion"),
                        tipo_movimiento_id = item.getLong("tipoMovimientoId"),
                        tipo_movimiento_inventario = item.getLong("tipoMovimientoInventario"),
                        folio = item.getLong("folio"),
                        formato_impresion = item.getString("formatoImpresion"),
                        serie_devolucion_id = item.getLong("serieDevolucionId"),
                        activa = item.getBooleanAsLong("activa"),
                        es_consecutiva = item.getBooleanAsLong("esConsecutiva"),
                        tipo_serie = item.getString("tipoSerie"),
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar serie_folio: ${e.message}")
                    println("Registro problemático: $item")
                    // Implementar 'updateSeriesFolios' o 'upsert'
                }
            }
        }
        println("✅ Datos guardados para: series_folios")
    }


    private fun saveSeriesFoliosUsuariosData(data: JsonArray) {
        val seriesUserQueries = database.series_folios_usuariosQueries

        seriesUserQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    // Clave primaria compuesta
                    seriesUserQueries.insertSeriesFoliosUsuarios(
                        serie_id = item.getLong("serieId") ?: 0L,
                        usuario_id = item.getLong("usuarioId") ?: 0L,
                        puede_usar = item.getBooleanAsLong("puedeUsar") ?: 1L,
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar serie_folio_usuario: ${e.message}")
                    println("Registro problemático: $item")
                    // Implementar 'updateSeriesFoliosUsuarios' o 'upsert'
                }
            }
        }
        println("✅ Datos guardados para: series_folios_usuarios")
    }

    private fun saveStockProductosData(data: JsonArray) {
        val stockQueries = database.stock_productosQueries

        stockQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    // Clave primaria compuesta (orden invertido en .sq pero no importa para el insert)
                    stockQueries.insertStockProductos(
                        id_ubicacion = item.getLong("idUbicacion") ?: 0L,
                        id_producto = item.getLong("idProducto") ?: 0L,
                        cantidad = item.getDouble("cantidad") ?: 0.0,
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar stock_producto: ${e.message}")
                    println("Registro problemático: $item")
                    // Implementar 'updateStockProductos' o 'upsert'
                }
            }
        }
        println("✅ Datos guardados para: stock_productos")
    }

    private fun saveUnidadesMedidaData(data: JsonArray) {
        val unidadesQueries = database.unidades_medidaQueries

        unidadesQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    unidadesQueries.insertUnidadesMedida(
                        id_unidad = item.getLong("idUnidad") ?: 0L,
                        nombre = item.getString("nombre"),
                        clave_sat = item.getString("claveSat"),
                        abreviatura = item.getString("abreviatura"),
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar unidad_medida: ${e.message}")
                    println("Registro problemático: $item")
                    // Implementar 'updateUnidadesMedida' o 'upsert'
                }
            }
        }
        println("✅ Datos guardados para: unidades_medida")
    }


    private fun saveProductosKitsData(data: JsonArray) {
        val kitsQueries = database.productos_kitsQueries

        kitsQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject
                try {
                    kitsQueries.insertProductosKits(
                        id_kit_detalle = item.getLong("idKitDetalle") ?: 0L,
                        id_producto_kit = item.getLong("idProductoKit") ?: 0L,
                        id_producto_componente = item.getLong("idProductoComponente") ?: 0L,
                        cantidad = item.getDouble("cantidad") ?: 0.0,
                        orden = item.getLong("orden") ?: 0L,
                        created_at = item.getString("createdAt"),
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar kit detalle: ${e.message}")
                    println("Registro problemático: $item")
                }
            }
        }
        println("✅ Datos guardados para: productos_kits")
    }

    fun JsonObject.getString(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it != "null" }
    }

    /** Extrae un Long o null de un JsonObject */
    fun JsonObject.getLong(key: String): Long? {
        return this[key]?.jsonPrimitive?.longOrNull
    }

    /** Extrae un Int o null de un JsonObject */
    fun JsonObject.getInt(key: String): Int? {
        return this[key]?.jsonPrimitive?.intOrNull
    }

    /** Extrae un Double o null de un JsonObject */
    fun JsonObject.getDouble(key: String): Double? {
        return this[key]?.jsonPrimitive?.doubleOrNull
    }

    /** Extrae un Boolean o null de un JsonObject */
    fun JsonObject.getBoolean(key: String): Boolean? {
        return this[key]?.jsonPrimitive?.booleanOrNull
    }

    fun JsonObject.getBooleanAsLong(key: String): Long? {
        val element = this[key] ?: return null // No key, return null

        // 1. Intenta como booleano (true/false)
        val boolVal = element.jsonPrimitive.booleanOrNull
        if (boolVal != null) {
            return if (boolVal) 1L else 0L
        }

        // 2. Intenta como número (1/0)
        val longVal = element.jsonPrimitive.longOrNull
        if (longVal != null) {
            // Acepta 1 como true, cualquier otra cosa (incluyendo 0) como false
            return if (longVal == 1L) 1L else 0L
        }

        // 3. Intenta como string ("true"/"false", "1"/"0", "t"/"f")
        val stringVal = element.jsonPrimitive.contentOrNull?.lowercase()
        return when (stringVal) {
            "true", "1", "t" -> 1L
            "false", "0", "f" -> 0L
            else -> null // No se pudo parsear
        }
    }

    private fun saveMarcasData(data: JsonArray) {
        val marcasQueries = database.marcasQueries

        marcasQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    marcasQueries.insertMarcas(
                        id_marca = item.getLong("idMarca")
                            ?: 0L, // Ajustado al nombre real del JSON
                        nombre = item.getString("nombre") ?: "",
                        updated_at = item.getString("updatedAt") ?: ""
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar marca: ${e.message}")
                    println("Registro problemático: $item")
                    // Implementar 'updateMarcas' o 'upsert'
                }
            }
        }
        println("✅ Datos guardados para: marcas")
    }

    private fun saveClientesUbicacionesData(data: JsonArray) {
        val ubicacionesQueries = database.clientes_ubicacionesQueries

        ubicacionesQueries.transaction {
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject
                try {
                    ubicacionesQueries.insertClientesUbicaciones(
                        id_cliente_ubicacion = item.getLong("idClienteUbicacion") ?: 0L,
                        id_cliente = item.getLong("idCliente") ?: 0L,
                        alias = item.getString("alias"),
                        activo = item.getBooleanAsLong("activo"), // Usa tu extensión existente
                        contacto_persona = item.getString("contactoPersona"),
                        contacto_telefono = item.getString("contactoTelefono"),
                        direccion = item.getString("direcccion"), // Nota: el JSON trae 3 'c' (direcccion)
                        no_exterior = item.getString("noExterior"),
                        no_interior = item.getString("noInterior"),
                        colonia = item.getString("colonia"),
                        localidad = item.getString("localidad"),
                        municipio = item.getString("municipio"),
                        estado = item.getString("estado"),
                        pais = item.getString("pais"),
                        codigo_postal = item.getString("codigoPostal"),
                        referencia = item.getString("referencia"),
                        latitud = item.getDouble("latitud") ?: 0.0,
                        longitud = item.getDouble("longitud") ?: 0.0,
                        updated_at = item.getString("updatedAt")
                    )
                } catch (e: Exception) {
                    println("❌ Error al insertar ubicación de cliente: ${e.message}")
                }
            }
        }
        println("✅ Datos guardados para: clientes_ubicaciones")
    }
    // Archivo: SyncViewModel.kt

    private fun savePedidosData(data: JsonArray) {
        // Asume que tienes un 'pedidosQueries' configurado para tu tabla local
        val pedidosFolioSerieQueries = database.folioSeries_pedidosQueries

        // 🚀 Envuelto en una transacción para inserciones masivas más rápidas
        pedidosFolioSerieQueries.transaction {
            // Itera sobre los registros
            data.forEach { jsonElement ->
                val item = jsonElement.jsonObject

                try {
                    val serieId = item.getLong("serieId")
                    val folio = item.getLong("folio")
                    val updatedAt = item.getString("updatedAt")
                    // ⚠️ Validación mínima: todos los campos de la proyección son necesarios.
                    if (serieId == null || folio == null || updatedAt == null) {
                        println("❌ Error: serieId, folio o updatedAt es nulo, omitiendo registro: $item")
                        return@forEach // Salta al siguiente elemento
                    }

                    // Llama al comando de UPSERT que debe estar definido en tu archivo pedidos.sq.
                    // Este comando debe manejar los demás campos de la tabla Pedidos
                    // (ej: cliente_id, total_solicitado) proporcionando valores DEFAULT o NULL.
                    pedidosFolioSerieQueries.upsertPedidos(
                        serie_id = serieId,
                        folio = folio,
                        updated_at = updatedAt
                    )
                    // This catalog maintains only the auxiliary series/folio cache.
                    // Commercial states are reconciled exclusively by PedidoEstadosSync.

                } catch (e: Exception) {
                    // Captura errores por si un registro falla
                    println("❌ Error al insertar/actualizar pedido (sync): ${e.message}")
                    println("Registro problemático: $item")
                    e.printStackTrace()
                }
            }
        }
        println("✅ Datos guardados para: pedidos (sincronización de catálogo)")
    }


    var currentPedido by mutableStateOf("")

    fun syncPedidosPendientes() {
        scope.launch {
            syncMutex.withLock {
            val errores = mutableListOf<String>()
            try {
                isLoading = true
                message = "Buscando pedidos pendientes..."
                progress = 0f

                val pedidos = database.pedidosQueries.getPedidosPendientesSync().executeAsList()
                if (pedidos.isEmpty()) {
                    pedidoEstadosSync.sincronizar()
                    message = "✅ Estados ERP actualizados. No hay pedidos pendientes por sincronizar"
                    return@launch
                }

                val total = pedidos.size
                var count = 0

                for (pedido in pedidos) {
                    currentPedido = "Pedido ${pedido.folio}"
                    message = "Sincronizando $currentPedido..."

                    val cola = PedidoComercialSync(database)
                    val resultado = cola.enviar(pedido.id_pedido) { payload ->
                        val pedidosUrl = basePedidosUrl ?: "${configRepository.requireBaseUrl()}/api/sync/pedidos"
                        val response = client.post(pedidosUrl) {
                            contentType(ContentType.Application.Json)
                            setBody(payload)
                        }
                        val body = runCatching { response.body<JsonObject>() }.getOrDefault(JsonObject(emptyMap()))
                        Pair(response.status.value, body)
                    }
                    errores += "Pedido ${pedido.folio}: $resultado"

                    count++
                    progress = count.toFloat() / total.toFloat()
                }
                pedidoEstadosSync.sincronizar()
                if (errores.isNotEmpty()) {
                    message = errores.joinToString("\n")
                } else {
                    message = "✅ No se sincronizó ningún pedido"
                }

            } catch (e: Exception) {
                message = "❌ Error general: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
                onSyncComplete()
            }
            }
        }
    }

    private fun getLastUpdatedAtProductos(): String? {
        // 1. Ejecutar la query, que devuelve el objeto 'GetMaxUpdatedAt?'
        val resultObject = database.productosQueries.getMaxUpdatedAt().executeAsOneOrNull()

        // 2. Acceder a la nueva propiedad con el alias 'max_updated_at'.
        return resultObject?.max_updated_at // <--- ¡USANDO EL ALIAS DEFINIDO!
    }


}
