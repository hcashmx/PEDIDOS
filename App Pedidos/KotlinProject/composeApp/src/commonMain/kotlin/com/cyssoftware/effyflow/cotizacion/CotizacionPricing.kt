package com.cyssoftware.effyflow.cotizacion

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Mirrors CotizacionPreciosErp and the existing Venta commercial resolvers over one immutable v3 snapshot. */
class CotizacionPricing(private val data: JsonObject) {
    data class Result(
        val customerPriceTypeId: Long,
        val listUnitPrice: BigDecimal,
        val volumeApplied: Boolean,
        val volumeRuleId: Long?,
        val volumeAllowsPromotion: Boolean?,
        val promotionApplied: Boolean,
        val promotionId: Long?,
        val promotionPriority: Long?,
        val presentationFactor: BigDecimal,
        val baseQuantity: BigDecimal,
        val offeredUnitPrice: BigDecimal,
        val grossLineAmount: BigDecimal,
        val manualDiscountAmount: BigDecimal,
        val netLineAmount: BigDecimal,
        val manualDiscountPercent: BigDecimal
    )

    private data class VolumeRule(
        val id: Long, val productId: Long, val sourceType: Long?, val destinationType: Long,
        val minimum: BigDecimal, val active: Boolean, val from: LocalDate?, val to: LocalDate?,
        val priority: Long?, val allowsPromotion: Boolean?, val allowsManualDiscount: Boolean?, val maxAdditionalDiscount: BigDecimal?
    )

    private data class Promotion(
        val id: Long, val priority: Long?, val valueType: String?, val value: BigDecimal,
        val from: LocalDate?, val to: LocalDate?, val active: Boolean, val status: String?, val benefit: String?
    )

    fun calculate(
        customerId: Long, agentId: Long?, productId: Long, presentationId: Long?,
        quantity: BigDecimal, date: LocalDate, manualDiscountPercent: BigDecimal
    ): Result {
        require(quantity.signum() > 0) { "La cantidad debe ser mayor que cero." }
        require(manualDiscountPercent.signum() >= 0 && manualDiscountPercent <= HUNDRED) { "El descuento debe estar entre 0 y 100%." }
        try { manualDiscountPercent.setScale(2, RoundingMode.UNNECESSARY) }
        catch (_: ArithmeticException) { error("El ERP admite descuentos con hasta dos decimales.") }

        val customer = rows("clientes_lista").firstOrNull { it.long("id_cliente") == customerId }
            ?: error("El cliente no pertenece a esta revisión comercial.")
        require(customer.bool("activo")) { "El cliente estaba inactivo en la revisión comercial." }
        val snapshotAgent = customer.longOrNull("agente_id")
        if (snapshotAgent != null && agentId != null) require(snapshotAgent == agentId) {
            "El agente no corresponde al cliente en esta revisión comercial."
        }
        val typeId = customer.long("numero_precio_id")
        require(rows("tipos_precio").any { it.long("id_tipo_precio") == typeId }) {
            "La lista histórica del cliente no existe en esta revisión."
        }

        val product = rows("productos").firstOrNull { it.long("id_producto") == productId }
            ?: error("El producto no pertenece a esta revisión comercial.")
        require(product.bool("activo")) { "El producto estaba inactivo en la revisión comercial." }
        val listPrice = rows("precios_lista").firstOrNull {
            it.long("producto_id") == productId && it.long("tipo_precio_id") == typeId
        }?.decimal("precio") ?: error("El producto no tiene precio para la lista histórica del cliente.")
        require(listPrice.signum() > 0) { "El precio de lista debe ser positivo." }

        val factor = factor(productId, presentationId)
        val baseQuantity = number(quantity.multiply(factor))
        val volume = resolveVolume(productId, typeId, baseQuantity, date, listPrice)
        val promo = if (volume.rule != null && volume.rule.allowsPromotion != true) null
            else resolvePromotion(productId, typeId, date, volume.price)

        val effectiveBase = promo?.first ?: volume.price.setScale(PRICE_SCALE, ROUNDING)
        val commercialGrossUnit = number(effectiveBase.multiply(factor))
        // ERP's price-reference contract compares against CalculoFiscalService.baseFiscal.
        // Mirror only its included-tax base normalization; no tax totals or snapshots are calculated here.
        val ivaIncluido = rows("configuracion_comercial").firstOrNull()?.get("iva_incluido")
            ?.jsonPrimitive?.contentOrNull == "1"
        val offeredUnit = if (ivaIncluido) {
            val includedRate = rows("impuestos_producto").asSequence()
                .filter { it.longOrNull("id_producto") == productId }
                .filter { it.stringOrNull("tipo_factor").equals("TASA", true) && it.longOrNull("signo") == 1L }
                .mapNotNull { it.decimalOrNull("tasa_o_cuota") }
                .fold(BigDecimal.ZERO) { total, rate -> total.add(rate) }
            if (includedRate.signum() > 0) number(commercialGrossUnit.divide(BigDecimal.ONE.add(includedRate), 6, ROUNDING))
            else commercialGrossUnit
        } else commercialGrossUnit
        val productMaximum = product.decimalOrNull("descuento_maximo") ?: BigDecimal.ZERO
        require(manualDiscountPercent <= productMaximum) {
            "El descuento excede el máximo del producto (${productMaximum.stripTrailingZeros().toPlainString()}%)."
        }
        volume.rule?.let { rule ->
            if (manualDiscountPercent.signum() > 0) {
                require(rule.allowsManualDiscount == true) { "La regla de volumen no permite descuento manual." }
                rule.maxAdditionalDiscount?.let { maximum -> require(manualDiscountPercent <= maximum) {
                    "El descuento excede el máximo adicional de la regla de volumen (${maximum.stripTrailingZeros().toPlainString()}%)."
                } }
            }
        }

        val gross = number(quantity.multiply(offeredUnit))
        val discount = number(gross.multiply(manualDiscountPercent).divide(HUNDRED, 12, ROUNDING))
        val net = number(gross.subtract(discount))
        return Result(typeId, listPrice, volume.rule != null, volume.rule?.id, volume.rule?.allowsPromotion,
            promo != null, promo?.second, promo?.third, factor, baseQuantity, offeredUnit, gross, discount, net, manualDiscountPercent)
    }

    private data class VolumeResult(val rule: VolumeRule?, val price: BigDecimal)
    private fun resolveVolume(productId: Long, customerTypeId: Long, quantity: BigDecimal, date: LocalDate,
                              customerPrice: BigDecimal): VolumeResult {
        val candidates = rows("precios_volumen").mapNotNull(::volumeRule)
            .filter { it.productId == productId && it.active }
            .filter { it.from == null || !it.from.isAfter(date) }
            .filter { it.to == null || !it.to.isBefore(date) }
            .filter { it.sourceType == null || it.sourceType == customerTypeId }
            .filter { it.minimum <= quantity }
            .sortedWith(compareBy<VolumeRule>({ if (it.sourceType == null) 1 else 0 }, { it.minimum.negate() },
                { it.priority ?: Long.MAX_VALUE }, { it.id }))
        if (candidates.isEmpty()) return VolumeResult(null, customerPrice)
        val winner = candidates.first()
        val tied = candidates.filter { (it.sourceType == null) == (winner.sourceType == null) &&
            it.minimum.compareTo(winner.minimum) == 0 && it.priority == winner.priority }
        check(tied.size == 1) { "Hay varias reglas de volumen con la misma prioridad para este producto." }
        val destination = rows("precios_lista").firstOrNull {
            it.long("producto_id") == productId && it.long("tipo_precio_id") == winner.destinationType
        }?.decimal("precio") ?: error("La revisión no contiene el precio destino de la regla de volumen.")
        require(destination.signum() > 0) { "El precio destino de volumen debe ser positivo." }
        require(destination <= customerPrice) { "El precio destino de volumen supera el precio de la lista del cliente." }
        return if (destination.compareTo(customerPrice) == 0) VolumeResult(null, customerPrice)
        else VolumeResult(winner, destination)
    }

    /** Returns [effective price, promotion id, priority], or null if no promotion wins. */
    private fun resolvePromotion(productId: Long, typeId: Long, date: LocalDate, base: BigDecimal): Triple<BigDecimal, Long, Long?>? {
        val products = rows("promociones_productos")
        val lists = rows("promociones_listas")
        val candidates = rows("promociones").mapNotNull(::promotion)
            .filter { it.active && it.status.equals("ACTIVA", true) && it.benefit.equals("DESCUENTO_DIRECTO", true) }
            .filter { it.from != null && !it.from.isAfter(date) && it.to != null && !it.to.isBefore(date) }
            .filter { promo -> lists.any { it.long("promocion_id") == promo.id && it.bool("activo") && it.long("tipo_precio_id") == typeId } }
            .mapNotNull { promo ->
                val productRow = products.firstOrNull { it.long("promocion_id") == promo.id && it.bool("activo") && it.long("producto_id") == productId }
                    ?: return@mapNotNull null
                val valueType = productRow.stringOrNull("tipo_valor_override")?.takeIf { it.isNotBlank() } ?: promo.valueType
                if (valueType != "PORCENTAJE" && valueType != "IMPORTE") return@mapNotNull null
                Triple(promo, valueType, productRow.decimalOrNull("valor_override") ?: promo.value)
            }
            .sortedWith(compareBy({ it.first.priority ?: Long.MAX_VALUE }))
        if (candidates.isEmpty()) return null
        val priority = candidates.first().first.priority
        val tied = candidates.filter { it.first.priority == priority }
        check(tied.size == 1) { "Hay varias promociones aplicables con la misma prioridad." }
        val (promo, type, value) = candidates.first()
        val effective = if (type == "PORCENTAJE") {
            val percentFactor = BigDecimal.ONE.subtract(value.divide(HUNDRED, 6, ROUNDING))
            base.multiply(percentFactor)
        } else base.subtract(value)
        return Triple(effective.max(BigDecimal.ZERO).setScale(PRICE_SCALE, ROUNDING), promo.id, promo.priority)
    }

    private fun factor(productId: Long, presentationId: Long?): BigDecimal {
        if (presentationId == null) {
            val baseUnitId = rows("productos").firstOrNull { it.long("id_producto") == productId }?.longOrNull("id_unidad")
            require(baseUnitId != null && rows("unidades").any { it.long("id_unidad") == baseUnitId }) {
                "El producto no tiene unidad base en esta revisi\u00f3n comercial."
            }
            return BigDecimal.ONE
        }
        val presentation = rows("presentaciones").firstOrNull { it.long("id_presentacion") == presentationId }
            ?: error("La presentación no pertenece a esta revisión comercial.")
        require(presentation.long("id_producto") == productId) { "La presentación no pertenece al producto." }
        require(presentation.boolOrNull("es_unidad_venta") != false) { "La presentación no está habilitada para venta." }
        val conversion = presentation.decimalOrNull("factor_conversion")?.takeIf { it.signum() > 0 }
            ?: presentation.decimalOrNull("cantidad_base")?.takeIf { it.signum() > 0 }
            ?: error("La presentación no tiene un factor histórico válido.")
        return conversion
    }

    private fun volumeRule(row: JsonObject): VolumeRule? = row.longOrNull("id")?.let { id ->
        VolumeRule(id, row.long("producto_id"), row.longOrNull("tipo_precio_origen_id"), row.long("tipo_precio_destino_id"),
            row.decimalOrNull("cantidad_minima") ?: BigDecimal.ZERO, row.bool("activo"), row.dateOrNull("fecha_inicio"),
            row.dateOrNull("fecha_fin"), row.longOrNull("prioridad"), row.boolOrNull("permite_promocion"),
            row.boolOrNull("permite_descuento_manual"), row.decimalOrNull("descuento_adicional_maximo_pct"))
    }

    private fun promotion(row: JsonObject): Promotion? = row.longOrNull("id")?.let { id ->
        Promotion(id, row.longOrNull("prioridad"), row.stringOrNull("tipo_valor"), row.decimalOrNull("valor") ?: BigDecimal.ZERO,
            row.dateOrNull("fecha_inicio"), row.dateOrNull("fecha_fin"), row.bool("activa"), row.stringOrNull("estado"),
            row.stringOrNull("tipo_beneficio"))
    }

    private fun rows(group: String): List<JsonObject> = data[group]?.jsonArray?.map { it.jsonObject }
        ?: error("Falta el conjunto histórico $group.")
    private fun number(value: BigDecimal) = value.setScale(6, ROUNDING)

    private fun JsonObject.long(name: String): Long = checkNotNull(longOrNull(name)) { "Falta $name en la revisión." }
    private fun JsonObject.longOrNull(name: String): Long? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull
    private fun JsonObject.decimal(name: String): BigDecimal = checkNotNull(decimalOrNull(name)) { "Falta $name en la revisión." }
    private fun JsonObject.decimalOrNull(name: String): BigDecimal? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toBigDecimalOrNull()
    private fun JsonObject.stringOrNull(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(name: String): Boolean = boolOrNull(name) == true
    private fun JsonObject.boolOrNull(name: String): Boolean? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.dateOrNull(name: String): LocalDate? = stringOrNull(name)?.let(LocalDate::parse)

    companion object {
        private val HUNDRED = BigDecimal("100")
        private const val PRICE_SCALE = 4
        private val ROUNDING = RoundingMode.HALF_UP
    }
}
