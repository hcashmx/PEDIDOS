package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.cotizacion.CotizacionPricing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.*

class CotizacionPricingTest {
    private val date = LocalDate.parse("2026-10-09")
    private fun calc(snapshot: String = baseSnapshot()) = CotizacionPricing(Json.parseToJsonElement(snapshot).jsonObject)
    private fun baseSnapshot() = """
        {"version_esquema":3,"clientes_lista":[{"id_cliente":20,"numero_precio_id":1,"activo":true,"agente_id":7}],
         "tipos_precio":[{"id_tipo_precio":1,"numero_precio":1},{"id_tipo_precio":2,"numero_precio":2},{"id_tipo_precio":3,"numero_precio":3}],
         "productos":[{"id_producto":10,"codigo":"A","nombre":"Artículo A","activo":true,"tipo":"P","tipo_articulo":"PRODUCTO","id_unidad":1,"descuento_maximo":20}],
         "unidades":[{"id_unidad":1,"nombre":"Pieza"}],"presentaciones":[],
         "precios_lista":[{"producto_id":10,"tipo_precio_id":1,"precio":100},{"producto_id":10,"tipo_precio_id":2,"precio":90},{"producto_id":10,"tipo_precio_id":3,"precio":80}],
         "precios_volumen":[],"promociones":[],"promociones_productos":[],"promociones_listas":[],
         "impuestos_producto":[],"kits_composicion":[],"configuracion_comercial":[{"iva_incluido":"0"}]}
    """.trimIndent()

    private fun volume(id: Int, min: Int, destination: Int, allowsPromo: Boolean = true,
                       allowsManual: Boolean = true, maxDiscount: Int? = null, priority: Int = 1) =
        """{"id":$id,"producto_id":10,"tipo_precio_origen_id":1,"tipo_precio_destino_id":$destination,"cantidad_minima":$min,"activo":true,"fecha_inicio":"2026-01-01","fecha_fin":"2026-12-31","prioridad":$priority,"permite_promocion":$allowsPromo,"permite_descuentos_cascada":true,"permite_descuento_manual":$allowsManual,"descuento_adicional_maximo_pct":${maxDiscount ?: "null"}}"""

    private fun promotion(id: Int, priority: Int, valueType: String, value: String, start: String = "2026-01-01",
                          end: String = "2026-12-31", productOverride: String = "") = Triple(
        """{"id":$id,"codigo":"P$id","nombre":"Promo $id","fecha_inicio":"$start","fecha_fin":"$end","activa":true,"estado":"ACTIVA","tipo_beneficio":"DESCUENTO_DIRECTO","tipo_valor":"$valueType","valor":$value,"prioridad":$priority,"permite_exceder_descuento_maximo":true}""",
        """{"id":$id,"promocion_id":$id,"producto_id":10,"activo":true$productOverride}""",
        """{"id":$id,"promocion_id":$id,"tipo_precio_id":1,"activo":true}"""
    )

    private fun run(pricing: CotizacionPricing, qty: String = "1", presentationId: Long? = null,
                    discount: String = "0") = pricing.calculate(20, 7, 10, presentationId,
        BigDecimal(qty), date, BigDecimal(discount))

    @Test fun clienteListaAndNormalPriceAreResolvedFromSnapshot() {
        val result = run(calc())
        assertEquals(1L, result.customerPriceTypeId)
        assertEquals("100", result.listUnitPrice.stripTrailingZeros().toPlainString())
        assertEquals("100.000000", result.offeredUnitPrice.toPlainString())
        assertEquals("100.000000", result.netLineAmount.toPlainString())
        assertFailsWith<IllegalStateException> { calc().calculate(999, 7, 10, null, BigDecimal.ONE, date, BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { calc().calculate(20, 99, 10, null, BigDecimal.ONE, date, BigDecimal.ZERO) }
    }

    @Test fun volumeThresholdAndTwoTiersUseHighestMinimumBeforePriority() {
        val data = baseSnapshot().replace("\"precios_volumen\":[]", "\"precios_volumen\":[${volume(1,5,2)},${volume(2,10,3)}]")
        val two = calc(data)
        val under = run(two, "4")
        assertFalse(under.volumeApplied)
        assertEquals("90.000000", run(two, "9").offeredUnitPrice.toPlainString())
        val hit = run(two, "10")
        assertTrue(hit.volumeApplied)
        assertEquals(2L, hit.volumeRuleId)
        assertEquals("80.000000", hit.offeredUnitPrice.toPlainString())

        // Same historical values as ERP's CotizacionPreciosHistoricosTest: list 850, volume list 700 at quantity 5.
        val erpFixture = baseSnapshot().replace("\"precio\":100", "\"precio\":850")
            .replace("\"precio\":90", "\"precio\":700")
            .replace("\"precios_volumen\":[]", "\"precios_volumen\":[${volume(7,5,2)}]")
        assertEquals("850.000000", run(calc(erpFixture), "4").offeredUnitPrice.toPlainString())
        assertEquals("700.000000", run(calc(erpFixture), "5").offeredUnitPrice.toPlainString())
    }

    @Test fun volumeSourceSpecificityPriorityAndAmbiguousTiesFollowErp() {
        val sourceSpecific = volume(1, 5, 2, priority = 9)
        val generic = volume(2, 5, 3, priority = 1).replace("\"tipo_precio_origen_id\":1", "\"tipo_precio_origen_id\":null")
        val data = baseSnapshot().replace("\"precios_volumen\":[]", "\"precios_volumen\":[$sourceSpecific,$generic]")
        assertEquals("90.000000", run(calc(data), "5").offeredUnitPrice.toPlainString())

        val sameRank = baseSnapshot().replace("\"precios_volumen\":[]", "\"precios_volumen\":[${volume(1,5,2,priority=1)},${volume(2,5,3,priority=1)}]")
        assertFailsWith<IllegalStateException> { run(calc(sameRank), "5") }
    }

    @Test fun presentationFactorFeedsBaseQuantityAndIsAppliedOnceToOfferedUnit() {
        val snapshot = baseSnapshot().replace("\"presentaciones\":[]", "\"presentaciones\":[{\"id_presentacion\":4,\"id_producto\":10,\"id_unidad\":2,\"factor_conversion\":5,\"cantidad_base\":5,\"es_unidad_venta\":true}]")
            .replace("\"precios_volumen\":[]", "\"precios_volumen\":[${volume(1,10,2)}]")
        val result = run(calc(snapshot), "2", 4)
        assertEquals("10.000000", result.baseQuantity.toPlainString())
        assertEquals("5", result.presentationFactor.stripTrailingZeros().toPlainString())
        assertEquals("450.000000", result.offeredUnitPrice.toPlainString())
        assertEquals("900.000000", result.netLineAmount.toPlainString())
    }

    @Test fun promotionPercentageAmountDateAndPriorityMatchHistoricalResolver() {
        val p = promotion(1, 5, "PORCENTAJE", "10")
        val percent = baseSnapshot().replace("\"promociones\":[]", "\"promociones\":[${p.first}]")
            .replace("\"promociones_productos\":[]", "\"promociones_productos\":[${p.second}]")
            .replace("\"promociones_listas\":[]", "\"promociones_listas\":[${p.third}]")
        assertEquals("90.000000", run(calc(percent)).offeredUnitPrice.toPlainString())
        val erpPriceFixture = baseSnapshot().replace("\"precio\":100", "\"precio\":850")
        val erpPromotion = promotion(3, 1, "PORCENTAJE", "10")
        val erpPromoJson = erpPriceFixture.replace("\"promociones\":[]", "\"promociones\":[${erpPromotion.first}]")
            .replace("\"promociones_productos\":[]", "\"promociones_productos\":[${erpPromotion.second}]")
            .replace("\"promociones_listas\":[]", "\"promociones_listas\":[${erpPromotion.third}]")
        assertEquals("765.000000", run(calc(erpPromoJson)).offeredUnitPrice.toPlainString())
        val amount = promotion(2, 1, "IMPORTE", "7")
        val amountJson = baseSnapshot().replace("\"promociones\":[]", "\"promociones\":[${amount.first}]")
            .replace("\"promociones_productos\":[]", "\"promociones_productos\":[${amount.second}]")
            .replace("\"promociones_listas\":[]", "\"promociones_listas\":[${amount.third}]")
        assertEquals("93.000000", run(calc(amountJson)).offeredUnitPrice.toPlainString())
        val out = promotion(3, 1, "IMPORTE", "7", end = "2026-10-08")
        val expired = baseSnapshot().replace("\"promociones\":[]", "\"promociones\":[${out.first}]")
            .replace("\"promociones_productos\":[]", "\"promociones_productos\":[${out.second}]")
            .replace("\"promociones_listas\":[]", "\"promociones_listas\":[${out.third}]")
        assertFalse(run(calc(expired)).promotionApplied)
        val best = promotion(4, 3, "IMPORTE", "8")
        val priority = amountJson.replace("${amount.first}", "${amount.first},${best.first}")
            .replace("${amount.second}", "${amount.second},${best.second}")
            .replace("${amount.third}", "${amount.third},${best.third}")
        assertEquals(2L, run(calc(priority)).promotionId)
        val tied = priority.replace("\"prioridad\":3", "\"prioridad\":1")
        assertFailsWith<IllegalStateException> { run(calc(tied)) }
    }

    @Test fun volumePromotionExclusionAndPermissionLimitsMatchErpPolicies() {
        val promo = promotion(1, 1, "PORCENTAJE", "10")
        val volumeBlocks = baseSnapshot().replace("\"precios_volumen\":[]", "\"precios_volumen\":[${volume(1,5,2,allowsPromo=false)}]")
            .replace("\"promociones\":[]", "\"promociones\":[${promo.first}]")
            .replace("\"promociones_productos\":[]", "\"promociones_productos\":[${promo.second}]")
            .replace("\"promociones_listas\":[]", "\"promociones_listas\":[${promo.third}]")
        val blocked = run(calc(volumeBlocks), "5")
        assertTrue(blocked.volumeApplied); assertFalse(blocked.promotionApplied)
        assertEquals("90.000000", blocked.offeredUnitPrice.toPlainString())
        val volumeAllows = volumeBlocks.replace("\"permite_promocion\":false", "\"permite_promocion\":true")
        assertEquals("81.000000", run(calc(volumeAllows), "5").offeredUnitPrice.toPlainString())
        val manualDenied = baseSnapshot().replace("\"precios_volumen\":[]", "\"precios_volumen\":[${volume(1,1,2,allowsManual=false)}]")
        assertFailsWith<IllegalArgumentException> { run(calc(manualDenied), "1", discount = "1") }
        val maxAdditional = baseSnapshot().replace("\"precios_volumen\":[]", "\"precios_volumen\":[${volume(1,1,2,maxDiscount=5)}]")
        assertEquals("5", run(calc(maxAdditional), "1", discount = "5").manualDiscountPercent.stripTrailingZeros().toPlainString())
        assertFailsWith<IllegalArgumentException> { run(calc(maxAdditional), "1", discount = "6") }
        assertFailsWith<IllegalArgumentException> { run(calc(), "1", discount = "20.01") }
    }

    @Test fun discountsAndIncludedTaxPriceNormalizationUseErpScaleAndRounding() {
        val promo = promotion(1, 1, "PORCENTAJE", "12.345")
        val data = baseSnapshot().replace("\"promociones\":[]", "\"promociones\":[${promo.first}]")
            .replace("\"promociones_productos\":[]", "\"promociones_productos\":[${promo.second}]")
            .replace("\"promociones_listas\":[]", "\"promociones_listas\":[${promo.third}]")
        val result = run(calc(data), discount = "2.50")
        assertEquals("87.655000", result.offeredUnitPrice.toPlainString())
        assertEquals("2.191375", result.manualDiscountAmount.toPlainString())
        assertEquals("85.463625", result.netLineAmount.toPlainString())
        val included = baseSnapshot().replace("\"iva_incluido\":\"0\"", "\"iva_incluido\":\"1\"")
            .replace("\"impuestos_producto\":[]", "\"impuestos_producto\":[{\"id_producto\":10,\"tipo_factor\":\"TASA\",\"signo\":1,\"tasa_o_cuota\":0.16}]")
        assertEquals("86.206897", run(calc(included)).offeredUnitPrice.toPlainString())
    }

    @Test fun kitIsPricedAsItsOwnCommercialProductWithoutExpansion() {
        val data = baseSnapshot().replace("\"PRODUCTO\"", "\"KIT\"")
            .replace("\"kits_composicion\":[]", "\"kits_composicion\":[{\"id_producto_kit\":10,\"id_producto_componente\":999,\"cantidad\":200}]")
        assertEquals("100.000000", run(calc(data)).offeredUnitPrice.toPlainString())
    }
}
