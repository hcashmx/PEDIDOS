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
    private val canonical = """{"clientes_lista":[{"activo":true,"id_cliente":30,"numero_precio_id":1}],"configuracion_comercial":[{"iva_incluido":"1"}],"impuestos_producto":[],"kits_composicion":[],"precios_lista":[{"precio":15.5,"producto_id":40,"tipo_precio_id":1}],"precios_volumen":[],"presentaciones":[],"productos":[{"activo":true,"descuento_maximo":5,"id_producto":40}],"promociones":[],"promociones_listas":[],"promociones_productos":[],"tipos_precio":[{"id_tipo_precio":1,"numero_precio":1}],"unidades":[],"version_esquema":3}"""
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
            client(payload(r2)).use { repo.descargarRevisionActual(it, "https://sync.test") }
            assertFails { repo.guardarBorrador(99, 20, 30, "2026-10-03", "REMISION", null,
                listOf(RenglonCotizacion(40, null, "3", "15.50")), stableUuid) }
            repo.guardarBorrador(10, 20, 30, "2026-10-03", "REMISION", "editado",
                listOf(RenglonCotizacion(40, null, "3", "15.50")), stableUuid)
            val after = repo.buscar(stableUuid)
            assertEquals(stableUuid, after.uuid)
            assertEquals("2026-10-03", after.fechaComercial)
            assertEquals(r1, after.revisionId)
            assertEquals("15.50", after.renglones.single().precioReferencia)
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
            assertNull(repo.revisionActiva())
            assertEquals(0L, EffyDatabase(driver).cotizacionesQueries.getRevisiones().executeAsList().size.toLong())
        } finally { driver.getConnection().close(); driver.close() }
    }

    @Test fun migrationIsRepeatableAndDoesNotTouchLegacyOrdersOrOfflineSessionTables() {
        val (driver, _) = fixture(payload())
        try {
            driver.execute(null, "INSERT INTO pedidos(cliente_id,agente_ventas_id,usuario_creo_id,serie_id,folio,fecha_creacion,plazo,tipo_documento,uuid_local) VALUES(1,2,3,4,5,'2026-10-01',0,'FACTURA','pedido-previo')", 0)
            LocalSchemaMigrations.apply(driver)
            assertEquals(1L, driver.executeQuery(null, "SELECT COUNT(*) FROM pedidos WHERE uuid_local='pedido-previo'", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value)
            assertEquals(1L, driver.executeQuery(null, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='LocalSession'", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value)
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
            first.guardarBorrador(12, 22, 32, "2026-10-03", "REMISION", "offline",
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
            assertEquals("23.40", quote.renglones.single().precioReferencia)
            assertEquals(quote.revisionId, repository.revisionActiva()?.id)
        } finally {
            reopened.getConnection().close(); reopened.close()
            Files.deleteIfExists(file)
        }
    }
}
