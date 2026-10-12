package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.cotizacion.*
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.db.LocalSchemaMigrations
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import java.nio.file.Files
import kotlin.test.*

class CotizacionRepositoryTest {
    private val canonical = """{"clientes_lista":[{"activo":true,"id_cliente":30,"numero_precio_id":1}],"configuracion_comercial":[{"iva_incluido":"1"}],"impuestos_producto":[],"kits_composicion":[],"precios_lista":[{"precio":15.5,"producto_id":40,"tipo_precio_id":1}],"precios_volumen":[],"presentaciones":[],"productos":[{"activo":true,"descuento_maximo":5,"id_producto":40,"id_unidad":1}],"promociones":[],"promociones_listas":[],"promociones_productos":[],"tipos_precio":[{"id_tipo_precio":1,"numero_precio":1}],"unidades":[{"id_unidad":1}],"version_esquema":3}"""
    private val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun payload(id: String = UUID.randomUUID().toString(), digest: String = hash, schema: Int = 3, groups: String = canonical): String =
        """{"id":"$id","versionEsquema":$schema,"publicadaEn":"2026-10-01T12:00:00Z","referenciaComercial":"sha256:$digest","huellaSha256":"$digest","datosComerciales":$groups}"""

    private fun fixture(body: String): Pair<JdbcSqliteDriver, CotizacionRepository> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        EffyDatabase.Schema.create(driver)
        LocalSchemaMigrations.apply(driver)
        return driver to CotizacionRepository(EffyDatabase(driver))
    }

    private fun client(body: String) = HttpClient(MockEngine {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test fun validV3HashIsDownloadedAndActivatedAtomicallyAndR1RemainsAfterR2() = runBlocking {
        val (driver, repo) = fixture(payload())
        val r1 = UUID.randomUUID().toString(); val r2 = UUID.randomUUID().toString()
        val r2Groups = canonical.replace("15.5", "20.5")
        val r2Hash = MessageDigest.getInstance("SHA-256").digest(r2Groups.toByteArray()).joinToString("") { "%02x".format(it) }
        try {
            client(payload(r1)).use { repo.descargarRevisionActual(it, "https://sync.test") }
            repo.guardarBorrador(10, 20, 30, "2026-10-02", "FACTURA", null,
                listOf(RenglonCotizacion(40, null, "2", "15.50")))
            val draft = repo.borradores().single()
            assertTrue(repo.borradores(99).isEmpty())
            assertEquals("BORRADOR_LOCAL", draft.estadoLocal)
            assertEquals(r1, draft.revisionId)
            assertEquals(64, draft.huella.length)
            val stableUuid = draft.uuid
            client(payload(r2, r2Hash, groups = r2Groups)).use { repo.descargarRevisionActual(it, "https://sync.test") }
            assertEquals("20.500000", repo.calcularPrecio(r2, 20, 30, 40, null, "1", "2026-10-03", "0").offeredUnitPrice.toPlainString())
            assertFails { repo.guardarBorrador(99, 20, 30, "2026-10-03", "REMISION", null,
                listOf(RenglonCotizacion(40, null, "3", "15.50")), stableUuid) }
            assertFails { repo.guardarBorrador(10, 21, 30, "2026-10-03", "REMISION", null,
                listOf(RenglonCotizacion(40, null, "3", "15.50")), stableUuid) }
            repo.guardarBorrador(10, 20, 30, "2026-10-03", "REMISION", "editado",
                listOf(RenglonCotizacion(40, null, "3", "15.50")), stableUuid)
            val after = repo.buscar(stableUuid)
            assertEquals(stableUuid, after.uuid)
            assertEquals("2026-10-03", after.fechaComercial)
            assertEquals(r1, after.revisionId)
            assertEquals("15.500000", after.renglones.single().precioReferencia)
            assertEquals("15.500000", repo.calcularPrecio(r1, 20, 30, 40, null, "1", "2026-10-03", "0").offeredUnitPrice.toPlainString())
            assertEquals("REMISION", after.documento)
            assertNotNull(EffyDatabase(driver).cotizacionesQueries.getRevision(r1).executeAsOneOrNull())
            assertEquals(r2, repo.revisionActiva()?.id)
        } finally { driver.getConnection().close(); driver.close() }
    }

    @Test fun invalidHashIncompletePackageAndUnsupportedSchemaNeverActivate() = runBlocking {
        val (driver, repo) = fixture(payload())
        try {
            client(payload(digest = "0".repeat(64))).use { c -> assertFails { repo.descargarRevisionActual(c, "https://sync.test") } }
            client(payload(schema = 2)).use { c -> assertFails { repo.descargarRevisionActual(c, "https://sync.test") } }
            client(payload(groups = "{\"version_esquema\":3}")).use { c -> assertFails { repo.descargarRevisionActual(c, "https://sync.test") } }
            val brokenReferences = canonical.replace("\"numero_precio_id\":1", "\"numero_precio_id\":99")
            val brokenHash = MessageDigest.getInstance("SHA-256").digest(brokenReferences.toByteArray()).joinToString("") { "%02x".format(it) }
            client(payload(digest = brokenHash, groups = brokenReferences)).use { c -> assertFails { repo.descargarRevisionActual(c, "https://sync.test") } }
            assertNull(repo.revisionActiva())
            assertEquals(0L, EffyDatabase(driver).cotizacionesQueries.getRevisiones().executeAsList().size.toLong())
        } finally { driver.getConnection().close(); driver.close() }
    }

    @Test fun migrationIsRepeatableAndDoesNotTouchLegacyOrdersOrOfflineSessionTables() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            EffyDatabase.Schema.create(driver)
            // Recreate the 04B database shape before this phase added per-line revision audit columns.
            driver.execute(null, "ALTER TABLE cotizacion_renglones DROP COLUMN revision_comercial_id", 0)
            driver.execute(null, "ALTER TABLE cotizacion_renglones DROP COLUMN revision_huella_sha256", 0)
            driver.execute(null, "INSERT INTO cotizaciones(uuid_local,usuario_autor_id,agente_id,cliente_id,fecha_comercial,capturada_en,revision_comercial_id,revision_huella_sha256,documento_solicitado,estado_local,modificado_en,subtotal_referencial) VALUES('old-quote',10,20,30,'2026-10-02','2026-10-02T12:00:00Z','old-r1','${"a".repeat(64)}','FACTURA','BORRADOR_LOCAL','2026-10-02T12:00:00Z','23.40')", 0)
            driver.execute(null, "INSERT INTO cotizacion_renglones(uuid_local,numero,producto_id,presentacion_id,cantidad,precio_referencia,descuento_solicitado) VALUES('old-quote',0,40,NULL,'1','23.40','0')", 0)
            LocalSchemaMigrations.apply(driver)
            driver.execute(null, "INSERT INTO pedidos(cliente_id,agente_ventas_id,usuario_creo_id,serie_id,folio,fecha_creacion,plazo,tipo_documento,uuid_local) VALUES(1,2,3,4,5,'2026-10-01',0,'FACTURA','pedido-previo')", 0)
            LocalSchemaMigrations.apply(driver)
            assertEquals(1L, driver.executeQuery(null, "SELECT COUNT(*) FROM pedidos WHERE uuid_local='pedido-previo'", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value)
            assertEquals(1L, driver.executeQuery(null, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='LocalSession'", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value)
            assertEquals(2L, driver.executeQuery(null, "SELECT COUNT(*) FROM pragma_table_info('cotizacion_renglones') WHERE name IN ('revision_comercial_id','revision_huella_sha256')", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value)
            assertEquals("old-r1", driver.executeQuery(null, "SELECT revision_comercial_id FROM cotizacion_renglones WHERE uuid_local='old-quote'", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!) }, 0).value)
            assertEquals("23.40", driver.executeQuery(null, "SELECT precio_referencia FROM cotizacion_renglones WHERE uuid_local='old-quote'", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!!) }, 0).value)
        } finally { driver.getConnection().close(); driver.close() }
    }

    @Test fun pendingQuoteAndRevisionSurviveDatabaseRestart() = runBlocking {
        val file = Files.createTempFile("cotizacion-persistencia", ".db")
        val url = "jdbc:sqlite:$file"
        var deviceId = ""
        val firstDriver = JdbcSqliteDriver(url)
        try {
            EffyDatabase.Schema.create(firstDriver)
            LocalSchemaMigrations.apply(firstDriver)
            val first = CotizacionRepository(EffyDatabase(firstDriver))
            deviceId = first.dispositivoId()
            val revisionId = UUID.randomUUID().toString()
            client(payload(revisionId)).use { first.descargarRevisionActual(it, "https://sync.test") }
            first.guardarBorrador(12, 22, 30, "2026-10-03", "REMISION", "offline",
                listOf(RenglonCotizacion(40, null, "1.5", "23.40")))
            assertEquals(1, first.borradores().size)
        } finally { firstDriver.getConnection().close(); firstDriver.close() }

        val reopened = JdbcSqliteDriver(url)
        try {
            LocalSchemaMigrations.apply(reopened)
            val repository = CotizacionRepository(EffyDatabase(reopened))
            assertEquals(deviceId, repository.dispositivoId())
            val quote = repository.borradores().single()
            assertEquals("2026-10-03", quote.fechaComercial)
            assertEquals("REMISION", quote.documento)
            assertEquals("offline", quote.observaciones)
            assertEquals("15.500000", quote.renglones.single().precioReferencia)
            assertEquals(quote.revisionId, repository.revisionActiva()?.id)
            val prepared = repository.prepararCaptura(quote.uuid)
            assertEquals(quote.uuid, prepared.uuid)
            assertEquals(quote.revisionId, prepared.revisionComercialId)
            assertEquals(quote.huella, prepared.revisionHuellaSha256)
            assertEquals(deviceId, prepared.dispositivoId)
            assertEquals(30L, prepared.clienteId)
            assertEquals("REMISION", prepared.documentoSolicitado)
            assertEquals("15.500000", prepared.renglones.single().precioReferencia.toPlainString())
        } finally {
            reopened.getConnection().close(); reopened.close()
            Files.deleteIfExists(file)
        }
    }
}
