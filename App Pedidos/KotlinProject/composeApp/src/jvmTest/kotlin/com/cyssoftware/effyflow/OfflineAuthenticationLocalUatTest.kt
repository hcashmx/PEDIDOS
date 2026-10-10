package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.login.data.*
import com.cyssoftware.effyflow.db.*
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.*
import kotlin.test.*

/** Opt-in multi-process Windows UAT; all ERP writes through HTTP, isolated SQLite. */
class OfflineAuthenticationLocalUatTest {
    @Test fun executeRequestedPhase() = runBlocking<Unit> {
        val phase=System.getenv("PED_AUTH_UAT_PHASE") ?: return@runBlocking
        fun env(key:String)=System.getenv(key) ?: error("Missing UAT setting $key")
        val file=Path.of(env("PED_AUTH_UAT_DB")); Files.createDirectories(file.parent)
        val url="jdbc:sqlite:${file.toAbsolutePath()}"
        val sync="http://localhost:8082"
        val props=Properties().apply { Files.newBufferedReader(Path.of(env("SYNC_LOCAL_CONFIG"))).use { load(it) } }
        val client=authHttpClient()
        try {
            ComercialFixture(url,phase=="prepare").use { f ->
                val local=AuthLocalDataSource(f.db)
                val repo=LoginRepository(local,{sync},client,Dispatchers.Unconfined)
                when(phase) {
                    "enroll" -> {
                        repo.synchronizeUsers(env("PED_AUTH_UAT_USERNAME"),env("PED_AUTH_UAT_PASSWORD"))
                        assertNull(repo.login(env("PED_AUTH_UAT_USERNAME"),env("PED_AUTH_UAT_PASSWORD")).error)
                        assertNotNull(local.findUser(env("PED_AUTH_UAT_USERNAME"))!!.verifier)
                        println("ONLINE_ENROLLMENT_VERIFIED")
                    }
                    "prepare" -> {
                        f.db.app_configQueries.upsertValue("base_url",sync)
                        val cases=Json.parseToJsonElement(Files.readString(Path.of(env("ERP_TEST_SCENARIOS_FILE")))).jsonObject
                        val normal=cases["normal"]!!.jsonObject["request"]!!.jsonObject
                        val header=normal["pedido"]!!.jsonObject; val lines=normal["detalles"]!!.jsonArray
                        val erp=props.getProperty("ERP_BASE_URL").trimEnd('/')
                        val auth=client.post("$erp/api/auth/login") { contentType(ContentType.Application.Json)
                            setBody(mapOf("usernameOrEmail" to props.getProperty("ERP_USERNAME"),"password" to props.getProperty("ERP_PASSWORD"))) }.body<JsonObject>()
                        val preview=client.post("$erp/api/pedidos/captura/preview") {
                            bearerAuth(auth["token"]!!.jsonPrimitive.content); contentType(ContentType.Application.Json)
                            setBody(buildJsonObject {
                                put("clienteId",header["cliente_id"]!!); put("agenteVentasId",header["agente_ventas_id"]!!)
                                put("almacenId",lines.first().jsonObject["almacen_id"]!!); put("serieId",header["serie_id"]!!)
                                put("documentoSolicitado",header["tipo_documento"]!!); put("tipoVenta",header["tipo_venta"]!!)
                                put("fechaCapturaComercial",header["fecha_creacion"]!!)
                                put("renglones",JsonArray(lines.map { element -> val line=element.jsonObject; buildJsonObject {
                                    put("productoId",line["producto_id"]!!); put("presentacionId",line["presentacion_id"] ?: JsonNull)
                                    put("cantidad",line["cantidad_solicitada"]!!); put("descuentoPorcentaje",0)
                                } }))
                            })
                        }
                        assertEquals(200,preview.status.value)
                        f.db.app_configQueries.upsertValue("uat_reference",buildJsonObject {
                            put("request",normal); put("preview",preview.body<JsonObject>()) }.toString())
                        assertFalse(local.hasCatalog())
                    }
                    "verify_enrollment" -> {
                        val user=assertNotNull(local.findUser(env("PED_AUTH_UAT_USERNAME")))
                        assertNotNull(user.verifier); assertNotNull(user.salt); assertNotNull(user.enrolledAt)
                        assertEquals("VENDEDOR",user.roles); assertNotNull(local.getSession())
                        assertNull(f.db.usersQueries.findById(user.userId).executeAsOne().pass)
                        println("ONLINE_ENROLLMENT_VERIFIED")
                    }
                    "offline" -> {
                        var requests=0
                        val offlineClient=mockAuthClient(MockEngine { requests++; error("Offline login attempted HTTP") })
                        try {
                            val offlineRepo=LoginRepository(local,{error("Offline login resolved Sync URL")},offlineClient,Dispatchers.Unconfined)
                            offlineRepo.logout()
                            assertNull(offlineRepo.login(env("PED_AUTH_UAT_USERNAME"),env("PED_AUTH_UAT_PASSWORD")).error)
                            assertNotNull(offlineRepo.login(env("PED_AUTH_UAT_USERNAME"),"deliberately-wrong").error)
                            assertEquals(0,requests)
                            if (f.db.pedidosQueries.getPedidosPendientesSync().executeAsList().isEmpty()) {
                                val reference=Json.parseToJsonElement(f.db.app_configQueries.getValue("uat_reference").executeAsOne()).jsonObject
                                val req=reference["request"]!!.jsonObject; val h=req["pedido"]!!.jsonObject
                                val preview=reference["preview"]!!.jsonObject
                                val date=java.time.OffsetDateTime.now().toString()
                                val uuid=UUID.randomUUID().toString()
                                f.db.transaction {
                                    f.db.pedidosQueries.insertPedidos(h["cliente_id"]!!.jsonPrimitive.long,h["agente_ventas_id"]!!.jsonPrimitive.long,
                                        local.getSession()!!.usuarioId!!.toLong(),null,h["serie_id"]!!.jsonPrimitive.long,0,date,null,null,null,0,"PENDIENTE_SYNC",
                                        "PED-AUTH-OFFLINE-02 UAT offline capture",null,preview["total"]!!.jsonPrimitive.double,null,null,uuid,null,date,"PENDIENTE_SYNC",
                                        h["tipo_documento"]!!.jsonPrimitive.content,null)
                                    val id=f.db.pedidosQueries.lastInsertRowId().executeAsOne()
                                    f.db.pedidosQueries.setTipoVenta(h["tipo_venta"]!!.jsonPrimitive.content,id)
                                    req["detalles"]!!.jsonArray.forEachIndexed { i, element ->
                                        val l=element.jsonObject; val calc=preview["renglones"]!!.jsonArray[i].jsonObject
                                        f.db.detalle_pedidosQueries.insertDetallePedidos(id,l["producto_id"]!!.jsonPrimitive.long,
                                            l["presentacion_id"]?.jsonPrimitive?.longOrNull,l["almacen_id"]!!.jsonPrimitive.long,l["cantidad_solicitada"]!!.jsonPrimitive.double,
                                            0.0,0.0,0.0,0.0,calc["precioUnitario"]!!.jsonPrimitive.double,calc["descuentoPorcentaje"]!!.jsonPrimitive.double,
                                            buildJsonObject { put("subtotal",calc["base"]!!); put("totalImpuestos",calc["impuestos"]!!) }.toString(),
                                            calc["total"]!!.jsonPrimitive.double,"PENDIENTE",date)
                                    }
                                }
                            }
                            val order=f.db.pedidosQueries.getPedidosPendientesSync().executeAsList().single()
                            java.time.OffsetDateTime.parse(order.fecha_creacion)
                            assertNotNull(order.uuid_local)
                            println("OFFLINE_LOGIN_HTTP_REQUESTS=0; PENDING_ORDER_UUID=${order.uuid_local}")
                            offlineRepo.logout(); assertNotNull(local.findUser(env("PED_AUTH_UAT_USERNAME"))!!.verifier)
                        } finally { offlineClient.close() }
                    }
                    "recovery" -> {
                        assertNull(repo.login(env("PED_AUTH_UAT_USERNAME"),env("PED_AUTH_UAT_PASSWORD")).error)
                        repo.synchronizeUsersWithSession()
                        val sender=HttpClient {
                            install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) }
                            configureSyncBearer(local::getTokens,local::updateTokens) { "$sync/api/auth/refresh" }
                        }
                        try {
                            val order=f.db.pedidosQueries.getPedidosPendientesSync().executeAsList().single()
                            val uuid=order.uuid_local; val date=order.fecha_creacion
                            f.queue.enviar(order.id_pedido) { wire ->
                                val response=sender.post("$sync/api/sync/pedidos") { contentType(ContentType.Application.Json); setBody(Json.parseToJsonElement(wire)) }
                                response.status.value to response.body<JsonObject>()
                            }
                            val accepted=f.db.pedidosQueries.findById(order.id_pedido).executeAsOne()
                            assertEquals("SYNCHRONIZED",accepted.synced_at); assertEquals(uuid,accepted.uuid_local); assertEquals(date,accepted.fecha_creacion)
                            var receivedState: Long? = null
                            val feed=PedidoEstadosSync(f.db) { after,until ->
                                val batch=cargarPedidoEstados(sender,"$sync/api/sync/pedidos/estados",after,until)
                                batch.data.firstOrNull { it.idPedido.toString()==accepted.uuid_server && it.estado=="RECIBIDO" }
                                    ?.let { receivedState=it.estadoId }
                                batch
                            }
                            feed.sincronizar()
                            assertEquals(assertNotNull(receivedState),f.db.pedidosQueries.findById(order.id_pedido).executeAsOne().estado_id)
                            assertNotNull(f.db.app_configQueries.getValue("pedidos_estados_evento_${order.id_pedido}").executeAsOneOrNull())
                            println("ERP_ACCEPTED_ID=${accepted.uuid_server}; UUID=$uuid; FOLIO=${accepted.folio}; PED_SYNC_R1=RECIBIDO")
                            f.db.app_configQueries.upsertValue("uat_accepted_id",accepted.uuid_server!!)
                        } finally { sender.close() }
                    }
                    "version" -> {
                        repo.synchronizeUsers(props.getProperty("ERP_USERNAME"),props.getProperty("ERP_PASSWORD"))
                        val user=assertNotNull(local.findUser(env("PED_AUTH_UAT_USERNAME")))
                        assertEquals(2L,user.credentialVersion); assertNull(user.verifier); assertNull(local.getSession())
                        assertNotNull(repo.login(user.username,env("PED_AUTH_UAT_OLD_PASSWORD")).error)
                        assertNull(repo.login(user.username,env("PED_AUTH_UAT_PASSWORD")).error)
                        repo.logout()
                        assertNull(repo.login(user.username,env("PED_AUTH_UAT_PASSWORD")).error)
                        assertNotNull(repo.login(user.username,env("PED_AUTH_UAT_OLD_PASSWORD")).error)
                        println("CREDENTIAL_VERSION_2_REENROLLED_AND_LOCAL_LOGIN_VERIFIED")
                    }
                    "disabled" -> {
                        repo.synchronizeUsers(props.getProperty("ERP_USERNAME"),props.getProperty("ERP_PASSWORD"))
                        assertNull(local.getSession())
                        assertEquals(0L,local.findUser(env("PED_AUTH_UAT_USERNAME"))!!.active)
                        assertNotNull(repo.login(env("PED_AUTH_UAT_USERNAME"),env("PED_AUTH_UAT_PASSWORD")).error)
                        println("USER_DISABLED_SESSION_REVOKED")
                    }
                    else -> error("Unknown UAT phase")
                }
            }
        } finally { client.close() }
    }
}
