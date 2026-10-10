package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.db.*
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.*
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.call.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.*

internal class ComercialFixture(url: String = JdbcSqliteDriver.IN_MEMORY, create: Boolean = true) : AutoCloseable {
    val driver = JdbcSqliteDriver(url)
    val db = EffyDatabase(driver)
    val queue = PedidoComercialSync(db)
    init { if (create) EffyDatabase.Schema.create(driver) }
    fun add(documento: String = "FACTURA", venta: String? = "CONTADO", fecha: String = "2026-10-09T21:35:42-06:00", producto: Long = 4, precio: Double = 152.31, total: Double = 176.6796): Long {
        var savedId=0L
        db.transaction {
        db.pedidosQueries.insertPedidos(1,1,1,null,10,20,fecha,null,null,null,0,"PENDIENTE_SYNC",
            "captura offline",null,total,null,null,UUID.randomUUID().toString(),null,fecha,"PENDIENTE_SYNC",documento,null)
        val id = db.pedidosQueries.lastInsertRowId().executeAsOne()
        db.pedidosQueries.setTipoVenta(venta, id)
        db.detalle_pedidosQueries.insertDetallePedidos(id,producto,null,1,1.0,0.0,0.0,0.0,0.0,precio,0.0,
            """{"subtotal":$precio,"totalImpuestos":${total-precio},"total":$total}""","$total".toDouble(),"PENDIENTE",fecha)
        savedId=id
        }
        return savedId
    }
    fun accepted(id: Long, total: String = "176.679600") = buildJsonObject {
        put("resultado", "ACEPTADO"); put("idPedido",900); put("uuidServer","900")
        put("uuidLocal",db.pedidosQueries.findById(id).executeAsOne().uuid_local)
        put("estado", "RECIBIDO"); put("estadoId",7); put("serieId",10); put("serie","P01")
        put("folio",45); put("total",Json.parseToJsonElement(total)); put("fechaRecepcion","2026-10-09T22:35:42-06:00"); put("version",1)
    }
    override fun close() { driver.getConnection().close(); driver.close() }
}

class PedidoComercialSyncTest {
    @Test fun dayFilterUsesOriginalLocalDayRatherThanUtcConversion() {
        ComercialFixture().use { f ->
            f.driver.execute(null,"INSERT INTO clientes(id_cliente,nombre) VALUES(1,'fixture')",0)
            val id=f.add(fecha="2026-10-09T23:35:42-06:00")
            val orders=f.db.pedidosQueries.getPedidosFiltrados(1,"2026-10-09","2026-10-09",null,null,null).executeAsList()
            assertEquals(id,orders.single().id_pedido)
        }
    }
    @Test fun upgradePreservesLegacyOrdersAndIsRepeatable() {
        ComercialFixture().use { f ->
            val id=f.add(); val original=f.db.pedidosQueries.findById(id).executeAsOne()
            for(column in listOf("tipo_venta","payload_comercial","resultado_comercial","total_erp","serie_erp","fecha_recepcion","version_erp")) {
                f.driver.execute(null,"ALTER TABLE pedidos DROP COLUMN $column",0)
            }
            LocalSchemaMigrations.apply(f.driver); LocalSchemaMigrations.apply(f.driver)
            val migrated=f.db.pedidosQueries.findById(id).executeAsOne()
            assertEquals(original.uuid_local,migrated.uuid_local); assertEquals(original.fecha_creacion,migrated.fecha_creacion)
            assertEquals(original.total_solicitado,migrated.total_solicitado)
            assertNull(migrated.tipo_venta); assertNull(migrated.payload_comercial)
            assertFailsWith<IllegalArgumentException> { f.queue.payload(id) }
        }
    }
    @Test fun failedCommercialSchemaMigrationIsNotSilenced() {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try { assertFails { LocalSchemaMigrations.apply(driver) } }
        finally { driver.getConnection().close(); driver.close() }
    }
    @Test fun documentAndSaleAreIndependentAndExact() {
        ComercialFixture().use { f ->
            for (doc in listOf("FACTURA","REMISION")) for (sale in listOf("CONTADO","CREDITO")) {
                val id = f.add(doc,sale)
                val header = Json.parseToJsonElement(f.queue.payload(id)).jsonObject["pedido"]!!.jsonObject
                assertEquals(doc,header["tipo_documento"]!!.jsonPrimitive.content)
                assertEquals(sale,header["tipo_venta"]!!.jsonPrimitive.content)
            }
        }
    }
    @Test fun capturePersistsOriginalOffsetAndChosenSale() {
        ComercialFixture().use { f ->
            f.driver.execute(null,"INSERT INTO agentes(id_agente,id_usuario) VALUES(1,1)",0)
            f.driver.execute(null,"INSERT INTO users(id) VALUES(1)",0)
            f.driver.execute(null,"INSERT INTO clientes(id_cliente,nombre) VALUES(1,'fixture')",0)
            f.driver.execute(null,"INSERT INTO series_folios(id_serie) VALUES(10)",0)
            val id = PedidoRepository(f.db).guardarPedidoLocal(f.db.agentesQueries.findById(1).executeAsOne(),
                f.db.usersQueries.findById(1).executeAsOne(),f.db.clientesQueries.findById(1).executeAsOne(),
                f.db.series_foliosQueries.selectAll().executeAsList().single(),20,emptyList(),0,"",TipoDocumento.REMISION,null,TipoPedido.CREDITO)
            val p = f.db.pedidosQueries.findById(assertNotNull(id)).executeAsOne()
            assertTrue(OffsetDateTime.parse(p.fecha_creacion).toInstant().isBefore(java.time.Instant.now().plusSeconds(1)))
            assertEquals("CREDITO",p.tipo_venta); assertEquals("REMISION",p.tipo_documento)
            assertNotNull(UUID.fromString(p.uuid_local))
        }
    }
    @Test fun invalidDocumentSaleAndLegacyDateNeverReachHttp() = runBlocking {
        ComercialFixture().use { f ->
            val cases = listOf(f.add(documento=""), f.add(venta=null), f.add(venta="NINGUNO"),f.add(fecha="2026-10-09T21:35:42"))
            for(id in cases) {
                f.queue.enviar(id) { error("Invalid capture must not be sent") }
                assertEquals("ERROR_NO_REINTENTABLE",f.db.pedidosQueries.findById(id).executeAsOne().synced_at)
            }
            assertTrue(f.db.pedidosQueries.getPedidosPendientesSync().executeAsList().isEmpty())
        }
    }
    @Test fun decimalWireHasNoBinaryTailsAndKitIsOneCommercialProduct() {
        ComercialFixture().use { f ->
            val id = f.add(producto=47,precio=0.1+0.2,total=0.3)
            val wire = f.queue.payload(id)
            assertFalse(wire.contains("0.30000000000000004"))
            val lines = Json.parseToJsonElement(wire).jsonObject["detalles"]!!.jsonArray
            assertEquals(1,lines.size); assertEquals(47,lines.single().jsonObject["producto_id"]!!.jsonPrimitive.long)
            assertEquals("0.3",lines.single().jsonObject["precio_unitario"]!!.jsonPrimitive.content)
        }
    }
    @Test fun normalVolumeAndPromotionKeepDefinitiveErpTotals() {
        ComercialFixture().use { f ->
            for ((price,total) in listOf(152.31 to "176.679600",145.0 to "841.000000",1080.0 to "1252.800000")) {
                val id=f.add(precio=price,total=1.0)
                f.queue.payload(id); f.queue.resultado(id,200,f.accepted(id,total))
                val p=f.db.pedidosQueries.findById(id).executeAsOne()
                assertEquals("SYNCHRONIZED",p.synced_at); assertEquals(total,p.total_erp)
                assertEquals(1.0,p.total_solicitado); assertEquals("900",p.uuid_server)
                assertEquals("P01",p.serie_erp); assertEquals(45,p.folio); assertEquals("1",p.version_erp)
                assertNotNull(p.fecha_recepcion)
            }
        }
    }
    @Test fun differencesPersistWithoutAcceptanceOrAutomaticRetry() {
        ComercialFixture().use { f ->
            val id=f.add(); val before=f.db.pedidosQueries.findById(id).executeAsOne()
            val payload=f.queue.payload(id)
            val response=Json.parseToJsonElement("""{"resultado":"DIFERENCIAS_COMERCIALES","detalleErp":{"total":180,"diferencias":[{"renglon":1,"precioApp":152.31,"precioErp":155,"descuentoApp":0,"descuentoErp":1,"impuestosApp":24.36,"impuestosErp":25,"totalApp":176.67,"totalErp":180,"motivo":"Precio actualizado"}]}}""").jsonObject
            f.queue.resultado(id,409,response)
            val after=f.db.pedidosQueries.findById(id).executeAsOne()
            assertEquals("PENDIENTE_REVISION",after.synced_at); assertNull(after.uuid_server); assertNull(after.total_erp)
            assertEquals(before.uuid_local,after.uuid_local); assertEquals(before.total_solicitado,after.total_solicitado)
            assertTrue(f.db.pedidosQueries.getPedidosPendientesSync().executeAsList().isEmpty())
            assertTrue(diferenciasComerciales(after.resultado_comercial).joinToString().contains("Precio actualizado"))
            f.db.pedidosQueries.retryRevision(id)
            assertEquals(payload,f.queue.payload(id)); assertEquals(1,f.db.pedidosQueries.getPedidosPendientesSync().executeAsList().size)
        }
    }
    @Test fun timeoutRetryAndRestartKeepExactPayloadDateUuidAndCorrelation() = runBlocking {
        val file=Files.createTempFile("pedido-comercial-", ".sqlite")
        try {
            val url="jdbc:sqlite:$file"
            var original=""; var id=0L
            ComercialFixture(url).use { f ->
                id=f.add(); original=f.queue.payload(id)
                f.queue.enviar(id) { throw java.net.SocketTimeoutException("after commit") }
                assertEquals("ERROR_REINTENTABLE",f.db.pedidosQueries.findById(id).executeAsOne().synced_at)
                assertEquals(1,f.db.pedidosQueries.getPedidosPendientesSync().executeAsList().size)
            }
            ComercialFixture(url,false).use { f ->
                f.queue.enviar(id) { wire -> assertEquals(original,wire); 200 to f.accepted(id) }
                assertEquals(original,f.queue.payload(id))
                assertEquals("2026-10-09T21:35:42-06:00",f.db.pedidosQueries.findById(id).executeAsOne().fecha_creacion)
                assertEquals(1,f.db.pedidosQueries.selectAll().executeAsList().size)
            }
            ComercialFixture(url,false).use { f -> assertEquals("900",f.db.pedidosQueries.findById(id).executeAsOne().uuid_server) }
        } finally { Files.deleteIfExists(file) }
    }
    @Test fun interruptedSendingRemainsInQueue() {
        ComercialFixture().use { f -> val id=f.add(); val wire=f.queue.payload(id); f.queue.enviando(id)
            assertEquals(id,f.db.pedidosQueries.getPedidosPendientesSync().executeAsList().single().id_pedido)
            assertEquals(wire,f.queue.payload(id)) }
    }
    @Test fun uuidConflictNeverDuplicatesOrChangesIdentity() {
        ComercialFixture().use { f -> val id=f.add(); val wire=f.queue.payload(id)
            f.queue.resultado(id,409,Json.parseToJsonElement("""{"resultado":"CONFLICTO","error":"UUID incompatible"}""").jsonObject)
            f.db.pedidosQueries.retryRevision(id)
            assertEquals("CONFLICTO",f.db.pedidosQueries.findById(id).executeAsOne().synced_at)
            assertEquals(wire,f.queue.payload(id)); assertEquals(1,f.db.pedidosQueries.selectAll().executeAsList().size)
            assertTrue(f.db.pedidosQueries.getPedidosPendientesSync().executeAsList().isEmpty()) }
    }
    @Test fun httpErrorsHaveBoundedQueuePolicy() {
        ComercialFixture().use { f -> for (http in listOf(400,401,403,422,408,429,500,503,504)) {
            val id=f.add(); f.queue.resultado(id,http,buildJsonObject { put("error","fixture"); put("origenError","ERP") })
            assertEquals(if(http in listOf(408,429) || http>=500) "ERROR_REINTENTABLE" else "ERROR_NO_REINTENTABLE",
                f.db.pedidosQueries.findById(id).executeAsOne().synced_at) } }
    }
    @Test fun malformedSuccessCannotMarkSynchronized() {
        ComercialFixture().use { f -> val id=f.add(); f.queue.resultado(id,200,buildJsonObject { put("resultado","ACEPTADO") })
            assertEquals("ERROR_REINTENTABLE",f.db.pedidosQueries.findById(id).executeAsOne().synced_at) }
    }
    @Test fun recibidoFeedPreservesAcceptanceAndFinancialResult() {
        ComercialFixture().use { f -> val id=f.add(); f.queue.resultado(id,200,f.accepted(id))
            val uuid=f.db.pedidosQueries.findById(id).executeAsOne().uuid_local
            val sync=PedidoEstadosSync(f.db) { _,_->error("no network") }
            sync.aplicarLote(PedidoEstadosBatch(11,11,false,listOf(PedidoEstadoEvento(11,900,uuid,7,"RECIBIDO","2026-10-09T22:00:00Z"))),0)
            assertEquals(11,sync.lastId()); assertEquals("SYNCHRONIZED",f.db.pedidosQueries.findById(id).executeAsOne().synced_at)
            assertEquals("176.679600",f.db.pedidosQueries.findById(id).executeAsOne().total_erp) }
    }
    @Test fun erpUnauthorizedDoesNotRefreshSyncSession() = runBlocking {
        var refresh=0
        val engine=MockEngine { request ->
            if(request.url.encodedPath.endsWith("refresh")) refresh++
            respond("""{"resultado":"ERROR_NO_REINTENTABLE","origenError":"ERP","error":"ERP 401"}""",
                HttpStatusCode.Unauthorized,headersOf(HttpHeaders.ContentType to listOf("application/json"),HttpHeaders.WWWAuthenticate to listOf("Bearer")))
        }
        val client=HttpClient(engine) {
            install(ContentNegotiation) { json() }
            configureSyncBearer({ "old" to "refresh" },{ _,_->error("Do not update Sync tokens") },{ "https://test.invalid/refresh" })
        }
        try { assertEquals(401,client.get("https://test.invalid/pedidos").status.value); assertEquals(0,refresh) }
        finally { client.close() }
    }
}
