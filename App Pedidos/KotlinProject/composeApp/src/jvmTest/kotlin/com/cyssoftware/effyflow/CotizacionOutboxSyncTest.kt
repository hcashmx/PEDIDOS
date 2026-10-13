package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.cotizacion.*
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.db.LocalSchemaMigrations
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.configureSyncBearer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class CotizacionOutboxSyncTest {
    private val commercial = """{"clientes_lista":[{"activo":true,"agente_id":20,"id_cliente":30,"numero_precio_id":1}],"configuracion_comercial":[{"iva_incluido":"0"}],"impuestos_producto":[],"kits_composicion":[],"precios_lista":[{"precio":15.5,"producto_id":40,"tipo_precio_id":1}],"precios_volumen":[],"presentaciones":[],"productos":[{"activo":true,"descuento_maximo":5,"id_producto":40,"id_unidad":1}],"promociones":[],"promociones_listas":[],"promociones_productos":[],"tipos_precio":[{"id_tipo_precio":1,"numero_precio":1}],"unidades":[{"id_unidad":1}],"version_esquema":3}"""
    private val commercialHash = sha(commercial)

    private data class Fixture(val driver: JdbcSqliteDriver, val db: EffyDatabase,
                               val repo: CotizacionRepository, val queue: CotizacionOutboxSync, val uuid: String, val revision: String)

    private suspend fun fixture(commercialData: String = commercial, presentationId: Long? = null): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        EffyDatabase.Schema.create(driver)
        LocalSchemaMigrations.apply(driver)
        val db = EffyDatabase(driver)
        val repo = CotizacionRepository(db)
        val revision = UUID.randomUUID().toString()
        val digest = sha(commercialData)
        val packageJson = """{"id":"$revision","versionEsquema":3,"publicadaEn":"2026-10-01T12:00:00Z","referenciaComercial":"sha256:$digest","huellaSha256":"$digest","datosComerciales":$commercialData}"""
        jsonClient(packageJson).use { repo.descargarRevisionActual(it, "https://sync.test") }
        repo.guardarBorrador(42, 20, 30, "2026-10-02", "FACTURA", "offline",
            listOf(RenglonCotizacion(40, presentationId, "1.250000", "15.50", "2.50")))
        val uuid = repo.borradores(42).single().uuid
        return Fixture(driver, db, repo, CotizacionOutboxSync(repo), uuid, revision)
    }

    private fun jsonClient(body: String) = HttpClient(MockEngine {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun response(uuid: String, revision: String, idem: String) = """{"resultado":"ACEPTADA","uuidLocal":"$uuid","idCotizacion":501,"estado":"BORRADOR","revisionComercialId":"$revision","version":0,"fechaComercial":"2026-10-02","documentoSolicitado":"FACTURA","subtotal":19.375,"impuestos":3.1,"total":22.475,"resultadoIdempotencia":"$idem","renglones":[{"productoId":40,"cantidad":1.25,"precioUnitario":15.5,"descuentoPorcentaje":2.5,"subtotal":19.375,"total":22.475}]}"""

    private fun httpClient(handler: suspend (String, String) -> Pair<Int, String>) =
        HttpClient(MockEngine { request ->
            val (status, body) = handler(request.url.encodedPath, request.body.asText())
            respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun OutgoingContent.asText(): String = when (this) {
        is TextContent -> text
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        else -> toString()
    }

    @Test fun payloadIsFrozenNumericAnd201PersistsAuthorizedErpResult() = runBlocking {
        val f = fixture()
        var sent = ""
        try {
            val client = httpClient { path, requestBody ->
                assertEquals("/api/sync/cotizaciones", path)
                sent = requestBody
                201 to response(f.uuid, f.revision, "CREADA")
            }
            client.use {
                val result = f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "access" to "refresh" }
                assertEquals("SINCRONIZADA", result.estado)
            }
            val payload = Json.parseToJsonElement(sent).jsonObject
            assertEquals(f.uuid, payload["uuid"]?.jsonPrimitive?.content)
            assertEquals(f.revision, payload["revisionComercialId"]?.jsonPrimitive?.content)
            assertEquals(commercialHash, payload["revisionHuellaSha256"]?.jsonPrimitive?.content)
            assertEquals(30, payload["clienteId"]?.jsonPrimitive?.int)
            assertEquals("FACTURA", payload["documentoSolicitado"]?.jsonPrimitive?.content)
            assertEquals("1.250000", payload["renglones"]!!.jsonArray.single().jsonObject["cantidad"]?.jsonPrimitive?.content)
            assertFalse(payload["renglones"]!!.jsonArray.single().jsonObject.containsKey("presentacionId"))
            assertFalse(payload["renglones"]!!.jsonArray.single().jsonObject.getValue("cantidad").jsonPrimitive.isString)
            assertEquals("2.50", payload["renglones"]!!.jsonArray.single().jsonObject["descuentoSolicitado"]?.jsonPrimitive?.content)
            val quote = f.repo.buscar(f.uuid)
            assertEquals("SINCRONIZADA", quote.estadoLocal)
            assertEquals(501L, quote.idErp)
            assertEquals(0L, quote.versionErp)
            assertEquals("BORRADOR", quote.estadoComercialErp)
            assertEquals("19.375", quote.subtotalErp)
            assertEquals("3.1", quote.impuestosErp)
            assertEquals("22.475", quote.totalErp)
            assertEquals("CREADA", quote.resultadoEnvio)
            assertFails { f.repo.guardarBorrador(42, 20, 30, "2026-10-02", "FACTURA", null, quote.renglones, f.uuid) }
            Unit
        } finally { f.driver.getConnection().close(); f.driver.close() }
    }

    @Test fun commercialPresentationPersistsRealIdAndFactorPriceInFrozenSyncPayload() = runBlocking {
        val withPresentation = commercial.replace(
            "\"presentaciones\":[]",
            """"presentaciones":[{"cantidad_base":12,"descripcion":"Caja","es_predeterminada":false,"es_unidad_venta":true,"factor_conversion":12,"id_presentacion":77,"id_unidad":1,"producto_id":40}]"""
        )
        val f = fixture(withPresentation, 77)
        try {
            val quote = f.repo.buscar(f.uuid)
            assertEquals(77L, quote.renglones.single().presentacionId)
            assertEquals("186.000000", quote.renglones.single().precioReferencia)
            val payload = Json.parseToJsonElement(f.queue.congelar(f.uuid, 42)).jsonObject
            val line = payload.getValue("renglones").jsonArray.single().jsonObject
            assertEquals(77L, line.getValue("presentacionId").jsonPrimitive.long)
            assertEquals("1.250000", line.getValue("cantidad").jsonPrimitive.content)
            assertEquals("186.000000", line.getValue("precioReferencia").jsonPrimitive.content)
            assertEquals(f.revision, payload.getValue("revisionComercialId").jsonPrimitive.content)
        } finally { f.driver.getConnection().close(); f.driver.close() }
    }

    @Test fun http200RecoveredIsPersistedWithoutCreatingAnotherLocalQuote() = runBlocking {
        val f = fixture()
        try {
            val client = httpClient { _, _ -> 200 to response(f.uuid, f.revision, "RECUPERADA") }
            client.use { assertEquals("SINCRONIZADA", f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "a" to "r" }.estado) }
            assertEquals("RECUPERADA", f.repo.buscar(f.uuid).resultadoEnvio)
            assertEquals(1, f.repo.borradores(42).size)
        } finally { f.driver.getConnection().close(); f.driver.close() }
    }

    @Test fun timeoutRetryUsesSameFrozenUuidAndPayloadAfterR2IsActivated() = runBlocking {
        val f = fixture()
        val captured = mutableListOf<String>()
        try {
            val failing = httpClient { _, requestBody ->
                captured += requestBody
                error("socket timeout")
            }
            failing.use { assertEquals("ERROR_REINTENTABLE", f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "a" to "r" }.estado) }
            val r2Data = commercial.replace("15.5", "18.75")
            val r2Hash = sha(r2Data)
            val r2 = UUID.randomUUID().toString()
            val body = """{"id":"$r2","versionEsquema":3,"publicadaEn":"2026-10-03T12:00:00Z","referenciaComercial":"sha256:$r2Hash","huellaSha256":"$r2Hash","datosComerciales":$r2Data}"""
            jsonClient(body).use { f.repo.descargarRevisionActual(it, "https://sync.test") }
            f.queue.reintentar(f.uuid, 42)
            val successful = httpClient { _, requestBody ->
                captured += requestBody
                200 to response(f.uuid, f.revision, "RECUPERADA")
            }
            successful.use { f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "a" to "r" } }
            assertEquals(2, captured.size)
            assertEquals(captured.first(), captured.last())
            assertEquals(f.revision, f.repo.buscar(f.uuid).revisionId)
        } finally { f.driver.getConnection().close(); f.driver.close() }
    }

    @Test fun automaticRetryWaitsForBackoffThenReusesFrozenPayload() = runBlocking {
        val f = fixture()
        var current = Instant.parse("2026-10-12T12:00:00Z")
        val queue = CotizacionOutboxSync(f.repo) { current }
        val requests = mutableListOf<String>()
        var call = 0
        try {
            val client = httpClient { _, body ->
                requests += body
                call++
                if (call == 1) 500 to "{}" else 200 to response(f.uuid, f.revision, "RECUPERADA")
            }
            client.use {
                assertEquals("ERROR_REINTENTABLE", queue.enviar(f.uuid, 42, it, "https://sync.test") { "a" to "r" }.estado)
                assertTrue(queue.enviarPendientes(42, it, "https://sync.test") { "a" to "r" }.isEmpty())
                current = current.plusSeconds(31)
                assertEquals("SINCRONIZADA", queue.enviarPendientes(42, it, "https://sync.test") { "a" to "r" }.single().estado)
            }
            assertEquals(2, requests.size)
            assertEquals(requests.first(), requests.last())
        } finally { f.driver.getConnection().close(); f.driver.close() }
    }

    @Test fun localCommitFailureAfter201RetriesIdempotentlyWithSamePayload() = runBlocking {
        val f = fixture()
        val payloads = mutableListOf<String>()
        try {
            f.driver.execute(null, "CREATE TRIGGER fail_outbox_success BEFORE UPDATE ON cotizacion_outbox WHEN NEW.estado_envio='SINCRONIZADA' BEGIN SELECT RAISE(ABORT,'simulated local write failure'); END", 0)
            val created = httpClient { _, body -> payloads += body; 201 to response(f.uuid, f.revision, "CREADA") }
            created.use { assertEquals("ERROR_REINTENTABLE", f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "a" to "r" }.estado) }
            assertNull(f.repo.buscar(f.uuid).idErp)
            f.driver.execute(null, "DROP TRIGGER fail_outbox_success", 0)
            f.queue.reintentar(f.uuid, 42)
            val recovered = httpClient { _, body -> payloads += body; 200 to response(f.uuid, f.revision, "RECUPERADA") }
            recovered.use { assertEquals("SINCRONIZADA", f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "a" to "r" }.estado) }
            assertEquals(2, payloads.size)
            assertEquals(payloads[0], payloads[1])
            assertEquals(501L, f.repo.buscar(f.uuid).idErp)
            assertEquals("RECUPERADA", f.repo.buscar(f.uuid).resultadoEnvio)
        } finally { f.driver.getConnection().close(); f.driver.close() }
    }

    @Test fun httpErrorClassesKeepOperationAndPreventUnauthorizedRetries() = runBlocking {
        val cases = listOf(400 to "REQUIERE_REVISION", 401 to "REQUIERE_AUTENTICACION", 403 to "REQUIERE_AUTORIZACION",
            409 to "REQUIERE_REVISION", 500 to "ERROR_REINTENTABLE")
        cases.forEach { (status, expected) ->
            val f = fixture()
            try {
                val engine = httpClient { _, _ -> status to """{"resultado":"TEST_$status","error":"Respuesta controlada."}""" }
                engine.use { assertEquals(expected, f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "a" to "r" }.estado) }
                val quote = f.repo.buscar(f.uuid)
                assertEquals(expected, quote.estadoLocal)
                if (status == 401) assertTrue(quote.ultimoError!!.contains("sesion remota fue rechazada"))
                else assertEquals("Respuesta controlada.", quote.ultimoError)
                assertNotNull(f.queue.outbox(f.uuid)?.payload_json)
                assertNull(quote.idErp)
            } finally { f.driver.getConnection().close(); f.driver.close() }
        }
    }

    @Test fun erpTechnical401RequiresReviewWithoutInvalidatingSellerSession() = runBlocking {
        val cases = listOf(
            "CREDENCIAL_TECNICA_ERP_INVALIDA" to "ERP rechazo su autenticacion tecnica.",
            "ASERCION_RS256_INVALIDA_O_VENCIDA" to "ERP rechazo la asercion comercial."
        )
        cases.forEach { (code, message) ->
            val f = fixture()
            try {
                val engine = httpClient { _, _ -> 401 to
                    """{"resultado":"$code","reintentable":false,"origenError":"ERP","error":"$message","detalle":{"requiereAutenticacion":false}}"""
                }
                engine.use {
                    assertEquals("REQUIERE_REVISION", f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "a" to "r" }.estado)
                }
                val quote = f.repo.buscar(f.uuid)
                val outbox = checkNotNull(f.queue.outbox(f.uuid))
                assertEquals("REQUIERE_REVISION", quote.estadoLocal)
                assertEquals(code, outbox.ultimo_error_codigo)
                assertEquals(message, quote.ultimoError)
                assertNull(outbox.proximo_intento_en)
                assertNull(quote.idErp)
            } finally { f.driver.getConnection().close(); f.driver.close() }
        }
    }

    @Test fun unauthenticatedSendQueuesWithoutHttpAndRetryAfterLoginKeepsAuthor() = runBlocking {
        val f = fixture()
        var calls = 0
        try {
            val engine = httpClient { _, _ -> calls++; error("No debe transmitir sin tokens") }
            engine.use {
                assertEquals("REQUIERE_AUTENTICACION", f.queue.enviar(f.uuid, 42, it, "https://sync.test") { null to null }.estado)
                assertEquals("REQUIERE_REVISION", f.queue.enviar(f.uuid, 99, it, "https://sync.test") { "other" to "other" }.estado)
            }
            assertEquals(0, calls)
            assertEquals(42L, f.repo.buscar(f.uuid).usuarioAutorId)
            assertFails { f.queue.reintentar(f.uuid, 99) }
            f.queue.reintentar(f.uuid, 42)
            val authenticated = httpClient { _, _ -> 201 to response(f.uuid, f.revision, "CREADA") }
            authenticated.use { assertEquals("SINCRONIZADA", f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "access" to "refresh" }.estado) }
            assertEquals("30", f.queue.outbox(f.uuid)?.payload_json?.let { Json.parseToJsonElement(it).jsonObject["clienteId"]?.jsonPrimitive?.content })
        } finally { f.driver.getConnection().close(); f.driver.close() }
    }

    @Test fun concurrentSendersClaimOnlyOnce() = runBlocking {
        val f = fixture()
        var calls = 0
        try {
            val engine = httpClient { _, _ ->
                calls++
                delay(80)
                201 to response(f.uuid, f.revision, "CREADA")
            }
            engine.use {
                val one = async { f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "a" to "r" } }
                val two = async { f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "a" to "r" } }
                one.await(); two.await()
            }
            assertEquals(1, calls)
            assertEquals("SINCRONIZADA", f.repo.buscar(f.uuid).estadoLocal)
        } finally { f.driver.getConnection().close(); f.driver.close() }
    }

    @Test fun abandonedSendingStateRecoversAfterRestartAndPayloadSurvivesSqlite() = runBlocking {
        val f = fixture()
        try {
            val frozen = f.queue.congelar(f.uuid, 42)
            val row = assertNotNull(f.queue.outbox(f.uuid))
            f.db.cotizacionesQueries.markOutboxSending("2026-10-02T13:00:00Z", f.uuid)
            f.db.cotizacionesQueries.markCotizacionSending("2026-10-02T13:00:00Z", f.uuid, 42)
            f.queue.recuperarEnviosInconclusos()
            assertEquals("ERROR_REINTENTABLE", f.queue.outbox(f.uuid)?.estado_envio)
            assertEquals("ENVIO_INTERRUPTO", f.queue.outbox(f.uuid)?.ultimo_error_codigo)
            assertEquals(frozen, f.queue.outbox(f.uuid)?.payload_json)
            assertEquals("ERROR_REINTENTABLE", f.repo.buscar(f.uuid).estadoLocal)
            assertTrue(row.payload_json.contains(f.uuid))
        } finally { f.driver.getConnection().close(); f.driver.close() }
    }

    @Test fun frozenOutboxPayloadSurvivesClosingAndReopeningSqlite() = runBlocking {
        val file = Files.createTempFile("cotizacion-outbox", ".sqlite")
        val url = "jdbc:sqlite:$file"
        val revision = UUID.randomUUID().toString()
        val packageJson = """{"id":"$revision","versionEsquema":3,"publicadaEn":"2026-10-01T12:00:00Z","referenciaComercial":"sha256:$commercialHash","huellaSha256":"$commercialHash","datosComerciales":$commercial}"""
        var frozen = ""
        var uuid = ""
        try {
            val firstDriver = JdbcSqliteDriver(url)
            try {
                EffyDatabase.Schema.create(firstDriver)
                LocalSchemaMigrations.apply(firstDriver)
                val repository = CotizacionRepository(EffyDatabase(firstDriver))
                jsonClient(packageJson).use { repository.descargarRevisionActual(it, "https://sync.test") }
                repository.guardarBorrador(42, 20, 30, "2026-10-02", "REMISION", null,
                    listOf(RenglonCotizacion(40, null, "2.125", "15.50", "1.25")))
                uuid = repository.borradores(42).single().uuid
                frozen = CotizacionOutboxSync(repository).congelar(uuid, 42)
            } finally { firstDriver.getConnection().close(); firstDriver.close() }
            val reopened = JdbcSqliteDriver(url)
            try {
                LocalSchemaMigrations.apply(reopened)
                val repository = CotizacionRepository(EffyDatabase(reopened))
                val queue = CotizacionOutboxSync(repository)
                assertEquals(frozen, queue.outbox(uuid)?.payload_json)
                assertEquals("PENDIENTE_ENVIO", repository.buscar(uuid).estadoLocal)
                assertEquals(42L, repository.buscar(uuid).usuarioAutorId)
                assertEquals(revision, repository.buscar(uuid).revisionId)
            } finally { reopened.getConnection().close(); reopened.close() }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun bearerRefreshRetriesSamePostAndPersistsRenewedTokens() = runBlocking {
        val f = fixture()
        var access = "expired-access"; var refresh = "refresh-1"
        var refreshCalls = 0; var postCalls = 0
        try {
            val engine = MockEngine { request ->
                if (request.url.encodedPath == "/api/auth/refresh") {
                    refreshCalls++
                    assertTrue(request.body.asText().contains("refresh-1"))
                    respond("""{"accessToken":"access-2","refreshToken":"refresh-2"}""", HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                } else {
                    postCalls++
                    if (request.headers[HttpHeaders.Authorization] == "Bearer access-2") respond(response(f.uuid, f.revision, "CREADA"),
                        HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                    else respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Bearer"))
                }
            }
            val client = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureSyncBearer({ access to refresh }, { a, r -> access = a; refresh = r }, { "https://sync.test/api/auth/refresh" })
            }
            client.use { f.queue.enviar(f.uuid, 42, it, "https://sync.test") { access to refresh } }
            assertEquals(1, refreshCalls)
            assertTrue(postCalls >= 2)
            assertEquals("access-2", access); assertEquals("refresh-2", refresh)
            assertEquals("SINCRONIZADA", f.repo.buscar(f.uuid).estadoLocal)
        } finally { f.driver.getConnection().close(); f.driver.close() }
    }

    @Test fun failedBearerRefreshKeepsFrozenOutboxAndRequiresAuthentication() = runBlocking {
        val f = fixture()
        var postCalls = 0
        var refreshCalls = 0
        try {
            val engine = MockEngine { request ->
                if (request.url.encodedPath == "/api/auth/refresh") {
                    refreshCalls++
                    respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                } else {
                    postCalls++
                    respond("{}", HttpStatusCode.Unauthorized,
                        headersOf(HttpHeaders.WWWAuthenticate to listOf("Bearer"), HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString())))
                }
            }
            val client = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureSyncBearer({ "expired" to "refresh" }, { _, _ -> error("Failed refresh must not replace tokens") },
                    { "https://sync.test/api/auth/refresh" })
            }
            client.use {
                assertEquals("REQUIERE_AUTENTICACION", f.queue.enviar(f.uuid, 42, it, "https://sync.test") { "expired" to "refresh" }.estado)
            }
            assertEquals(1, postCalls)
            assertEquals(1, refreshCalls)
            assertNotNull(f.queue.outbox(f.uuid)?.payload_json)
            assertEquals("REQUIERE_AUTENTICACION", f.repo.buscar(f.uuid).estadoLocal)
        } finally { f.driver.getConnection().close(); f.driver.close() }
    }

}
