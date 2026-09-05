package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.db.SearchProductosConDetalleStock
import com.cyssoftware.effyflow.pedido.nuevoPedido.RenglonFiscalSnapshot
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.ImpuestoCalculado
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.PedidoLinea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PedidoRenglonContractTest {

    @Test
    fun presentacionDelRenglonUsaIdPresentacionNoIdUnidad() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        EffyDatabase.Schema.create(driver)
        val db = EffyDatabase(driver)

        db.presentaciones_productoQueries.insertPresentacionesProducto(
            id_presentacion = 31,
            id_producto = 27,
            id_unidad = 1,
            descripcion = "Pieza",
            factor_conversion = 1.0,
            cantidad_base = 1.0,
            es_unidad_venta = 1,
            es_predeterminada = 1,
            updated_at = "2026-08-21T00:00:00"
        )

        val presentacion = db.presentaciones_productoQueries
            .getPresentacionVentaPorProducto(27)
            .executeAsOne()

        assertEquals(27, presentacion.id_producto)
        assertEquals(1, presentacion.id_unidad)
        assertEquals(31, presentacion.id_presentacion)
        assertNotEquals(presentacion.id_unidad, presentacion.id_presentacion)
    }

    @Test
    fun snapshotFiscalDelRenglonConservaIvaDieciseis() {
        val linea = PedidoLinea(
            producto = productoOrt001(),
            cantidad = 1.0,
            precio = 560.0,
            descuento = 0.0,
            subtotal = 560.0,
            impuestos = listOf(
                ImpuestoCalculado(
                    idImpuesto = 1,
                    nombre = "IVA",
                    tipoAplicacion = "Traslado",
                    tipoFactor = "Tasa",
                    tasa = 0.16,
                    base = 560.0,
                    monto = 89.6,
                    cImpuesto = "002",
                    signo = 1
                )
            ),
            total = 649.6,
            presentacionId = 31,
            almacenId = 1
        )

        val json = Json.parseToJsonElement(RenglonFiscalSnapshot.serializar(linea)).jsonObject
        val impuesto = json["impuestos"]!!.jsonArray.single().jsonObject

        assertEquals("560.0", impuesto["base"]!!.jsonPrimitive.content)
        assertEquals("0.16", impuesto["tasa"]!!.jsonPrimitive.content)
        assertEquals("89.6", impuesto["importe"]!!.jsonPrimitive.content)
        assertEquals("002", impuesto["cImpuesto"]!!.jsonPrimitive.content)
        assertTrue(json["impuestos"]!!.jsonArray.isNotEmpty())
    }

    private fun productoOrt001() = SearchProductosConDetalleStock(
        id_producto = 27,
        codigo = "ORT-001",
        nombre = "ORT-001",
        descripcion = null,
        activo = 1,
        id_categoria = null,
        upc = null,
        largo = null,
        ancho = null,
        alto = null,
        grueso = null,
        peso = null,
        tipo = null,
        clave_sat = null,
        id_unidad = 1,
        costo_ultimo = null,
        costo_reposicion = null,
        costo_anterior = null,
        costo_promedio = null,
        descuento_max = 0.0,
        tipo_articulo = null,
        id_marca = null,
        id_medida = null,
        permite_negativo = null,
        inner_pack = null,
        master_pack = null,
        updated_at = null,
        marcaNombre = null,
        unidadNombre = "Pieza",
        categoriaNombre = "",
        precioUnitario = 560.0,
        existenciaTotal = 0.0,
        impuestosNombres = "IVA"
    )
}
