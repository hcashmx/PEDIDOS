package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URLClassLoader
import java.nio.file.*
import java.sql.*
import java.util.Properties
import java.util.UUID
import kotlin.test.*

/** Opt-in UAT. All ERP writes go through authenticated Sync/ERP HTTP. JDBC only reads. */
class PedidoComercialLocalIntegrationTest {
    @Test fun sqliteKotlinToRealSyncErpPostgresAndFeed() = runBlocking {
        if (System.getenv("PED_COM_SYNC_04_ENABLED") != "true") {
            println("PED-COM-SYNC-04 real integration disabled; enable explicitly in UAT")
            return@runBlocking
        }
        fun env(key: String) = System.getenv(key) ?: error("Missing UAT environment $key")
        val local = Properties().apply { Files.newBufferedReader(Path.of(env("SYNC_LOCAL_CONFIG"))).use { load(it) } }
        val erp = local.getProperty("ERP_BASE_URL").trimEnd('/')
        val sync = env("SYNC_TEST_BASE_URL").trimEnd('/')
        val cases = Json.parseToJsonElement(Files.readString(Path.of(env("ERP_TEST_SCENARIOS_FILE")))).jsonObject
        val client = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
        suspend fun post(url: String, payload: JsonObject, token: String? = null): Pair<Int,JsonObject> {
            val response = client.post(url) {
                contentType(ContentType.Application.Json); setBody(payload)
                if (token != null) bearerAuth(token)
            }
            return response.status.value to response.body<JsonObject>()
        }
        val loader = URLClassLoader(arrayOf(Path.of(env("POSTGRES_JDBC_JAR")).toUri().toURL()), javaClass.classLoader)
        val pgDriver = loader.loadClass("org.postgresql.Driver").getDeclaredConstructor().newInstance() as Driver
        val pg = pgDriver.connect(env("ERP_TEST_DB_URL"),Properties().apply {
            setProperty("user",env("DB_USERNAME")); setProperty("password",env("DB_PASSWORD"))
        })!!
        pg.isReadOnly = true
        fun query(sql: String, parameter: String? = null): List<Map<String,Any?>> = pg.prepareStatement(sql).use { stmt ->
            if (parameter != null) stmt.setString(1,parameter)
            stmt.executeQuery().use { rs -> buildList {
                while(rs.next()) add((1..rs.metaData.columnCount).associate { rs.metaData.getColumnLabel(it) to rs.getObject(it) })
            } }
        }
        try {
            val erpLogin = post("$erp/api/auth/login",buildJsonObject {
                put("usernameOrEmail",local.getProperty("ERP_USERNAME")); put("password",local.getProperty("ERP_PASSWORD"))
            })
            assertEquals(200,erpLogin.first,"ERP login")
            val erpToken=erpLogin.second["token"]!!.jsonPrimitive.content
            val syncLogin=post("$sync/api/auth/login",buildJsonObject {
                put("email",env("SYNC_TEST_EMAIL")); put("password",env("SYNC_TEST_PASSWORD"))
            })
            assertEquals(200,syncLogin.first,"Sync login")
            val token=syncLogin.second["accessToken"]!!.jsonPrimitive.content
            suspend fun send(wire: String) = post("$sync/api/sync/pedidos",Json.parseToJsonElement(wire).jsonObject,token)
            val sqlite=Files.createTempFile("ped-com-04-uat-", ".sqlite")
            val url="jdbc:sqlite:$sqlite"
            var timeoutId=0L; var timeoutWire=""; var timeoutRemote=""
            val evidence=mutableListOf<JsonObject>()
            ComercialFixture(url).use { f ->
                for(label in listOf("normal","factura","remision","volumen","promocion","diferencia","timeout","kit")) {
                    val fixture=cases[if(label in listOf("factura","remision","diferencia","timeout")) "normal" else label]!!.jsonObject
                    val request=fixture["request"]!!.jsonObject
                    val header=request["pedido"]!!.jsonObject
                    val inputLines=request["detalles"]!!.jsonArray
                    fun h(key:String)=header[key]!!.jsonPrimitive.content
                    val doc=if(label=="remision") "REMISION" else h("tipo_documento")
                    val uuid=UUID.randomUUID().toString()
                    val previewRequest=buildJsonObject {
                        put("clienteId",header["cliente_id"]!!); put("agenteVentasId",header["agente_ventas_id"]!!)
                        put("almacenId",inputLines.first().jsonObject["almacen_id"]!!); put("serieId",header["serie_id"]!!)
                        put("documentoSolicitado",doc); put("tipoVenta",h("tipo_venta"))
                        put("fechaCapturaComercial",h("fecha_creacion"))
                        put("renglones",JsonArray(inputLines.map { element -> val line=element.jsonObject; buildJsonObject {
                            put("productoId",line["producto_id"]!!); put("presentacionId",line["presentacion_id"] ?: JsonNull)
                            put("cantidad",line["cantidad_solicitada"]!!); put("descuentoPorcentaje",line["descuento"] ?: JsonPrimitive(0))
                        } }))
                    }
                    // Preview is fixture preparation only, never part of the application's send flow.
                    val preview=post("$erp/api/pedidos/captura/preview",previewRequest,erpToken)
                    assertEquals(200,preview.first,"Fixture preview $label")
                    val calculated=preview.second["renglones"]!!.jsonArray
                    if(label !in listOf("factura","remision","diferencia","timeout")) {
                        assertEquals(0,fixture["expected"]!!.jsonObject["precioUnitario"]!!.jsonPrimitive.content.toBigDecimal().compareTo(
                            calculated.first().jsonObject["precioUnitario"]!!.jsonPrimitive.content.toBigDecimal()),label)
                        assertEquals(fixture["expected"]!!.jsonObject["promocionCodigo"],calculated.first().jsonObject["promocionCodigo"],label)
                    }
                    var id=0L
                    f.db.transaction {
                        f.db.pedidosQueries.insertPedidos(h("cliente_id").toLong(),h("agente_ventas_id").toLong(),1,null,h("serie_id").toLong(),
                            9999,h("fecha_creacion"),null,null,null,0,"PENDIENTE_SYNC","PED-COM-SYNC-04 UAT $label",null,
                            preview.second["total"]!!.jsonPrimitive.double,null,null,uuid,null,h("fecha_creacion"),"PENDIENTE_SYNC",doc,null)
                        id=f.db.pedidosQueries.lastInsertRowId().executeAsOne()
                        f.db.pedidosQueries.setTipoVenta(h("tipo_venta"),id)
                        inputLines.forEachIndexed { i, element ->
                            val line=element.jsonObject; val calc=calculated[i].jsonObject
                            val snapshot=buildJsonObject { put("subtotal",calc["base"]!!); put("totalImpuestos",calc["impuestos"]!!) }.toString()
                            f.db.detalle_pedidosQueries.insertDetallePedidos(id,line["producto_id"]!!.jsonPrimitive.long,
                                line["presentacion_id"]?.jsonPrimitive?.longOrNull,line["almacen_id"]!!.jsonPrimitive.long,
                                line["cantidad_solicitada"]!!.jsonPrimitive.double,0.0,0.0,0.0,0.0,
                                if(label=="diferencia") 999999.99 else calc["precioUnitario"]!!.jsonPrimitive.double,
                                calc["descuentoPorcentaje"]!!.jsonPrimitive.double,snapshot,calc["total"]!!.jsonPrimitive.double,"PENDIENTE",h("fecha_creacion"))
                        }
                    }
                    val wire=f.queue.payload(id)
                    if(label=="timeout") {
                        // Lose the result after the real Sync -> ERP commit, then reopen SQLite before retry.
                        f.queue.enviar(id) { body ->
                            val actual=send(body); assertEquals(200,actual.first)
                            timeoutRemote=actual.second["idPedido"]!!.jsonPrimitive.content
                            throw java.net.SocketTimeoutException("UAT loses committed response")
                        }
                        assertEquals("ERROR_REINTENTABLE",f.db.pedidosQueries.findById(id).executeAsOne().synced_at)
                        timeoutId=id; timeoutWire=wire
                        continue
                    }
                    f.queue.enviar(id) { send(it) }
                    val saved=f.db.pedidosQueries.findById(id).executeAsOne()
                    val stored=query("SELECT p.id_pedido,p.uuid_local,p.documento_solicitado,CASE WHEN p.plazo > 0 THEN 'CREDITO' ELSE 'CONTADO' END AS tipo_venta,p.fecha_captura_comercial,p.total_solicitado AS total,e.estado FROM pedidos p JOIN pedido_estados e ON e.id_estado=p.estado_id WHERE p.uuid_local = ?",uuid)
                    if(label=="diferencia") {
                        assertEquals("PENDIENTE_REVISION",saved.synced_at); assertEquals(0,stored.size)
                        assertFalse(f.db.pedidosQueries.getPedidosPendientesSync().executeAsList().any { it.id_pedido==id })
                        evidence+=buildJsonObject { put("escenario",label); put("uuid",uuid); put("estado",saved.synced_at) }
                        continue
                    }
                    assertEquals("SYNCHRONIZED",saved.synced_at,"$label: ${saved.resultado_comercial}")
                    assertEquals(1,stored.size); val row=stored.single()
                    assertEquals(doc,row["documento_solicitado"]); assertEquals(h("tipo_venta"),row["tipo_venta"])
                    assertEquals(java.time.OffsetDateTime.parse(h("fecha_creacion")).toInstant(),(row["fecha_captura_comercial"] as java.sql.Timestamp).toInstant())
                    assertEquals(0,saved.total_erp!!.toBigDecimal().compareTo(row["total"].toString().toBigDecimal()))
                    val replay=send(wire); assertEquals(200,replay.first); assertEquals(saved.uuid_server,replay.second["idPedido"]!!.jsonPrimitive.content)
                    val altered=Json.parseToJsonElement(wire).jsonObject.toMutableMap()
                    altered["pedido"]=JsonObject(altered["pedido"]!!.jsonObject.toMutableMap().apply { put("observaciones",JsonPrimitive("captura diferente")) })
                    val conflict=send(JsonObject(altered).toString()); assertEquals(409,conflict.first)
                    assertEquals("CONFLICTO",conflict.second["resultado"]!!.jsonPrimitive.content)
                    val history=query("SELECT h.id_historial,e.estado FROM pedidos_estados_historial h JOIN pedido_estados e ON e.id_estado=h.estado_nuevo WHERE h.pedido_id = ?::bigint ORDER BY h.id_historial",saved.uuid_server)
                    assertTrue(history.isNotEmpty()); assertEquals("RECIBIDO",history.first()["estado"])
                    if(label=="kit") {
                        val detail=query("SELECT producto_id,impuestos::text AS snapshot FROM detalle_pedidos WHERE pedido_id = ?::bigint",saved.uuid_server)
                        assertEquals(1,detail.size); assertTrue(Json.parseToJsonElement(detail.single()["snapshot"].toString()).jsonObject.containsKey("kit"))
                    }
                    evidence+=buildJsonObject { put("escenario",label); put("uuid",uuid); put("idPedido",saved.uuid_server)
                        put("serie",saved.serie_erp); put("folio",saved.folio); put("total",saved.total_erp); put("historial",history.first()["id_historial"].toString()) }
                }
            }
            ComercialFixture(url,false).use { f ->
                assertEquals(timeoutWire,f.queue.payload(timeoutId))
                f.queue.enviar(timeoutId) { send(it) }
                val recovered=f.db.pedidosQueries.findById(timeoutId).executeAsOne()
                assertEquals("SYNCHRONIZED",recovered.synced_at); assertEquals(timeoutRemote,recovered.uuid_server)
                assertEquals(1,query("SELECT id_pedido FROM pedidos WHERE uuid_local = ?",recovered.uuid_local).size)
                evidence+=buildJsonObject { put("escenario","timeout/retry/reinicio"); put("idPedido",recovered.uuid_server); put("folio",recovered.folio); put("uuid",recovered.uuid_local) }
                val feed=PedidoEstadosSync(f.db) { after,until ->
                    val response=client.get("$sync/api/sync/pedidos/estados") {
                        bearerAuth(token); parameter("afterId",after); parameter("size",500); if(until!=null) parameter("untilId",until)
                    }
                    assertEquals(200,response.status.value); response.body<PedidoEstadosBatch>()
                }
                feed.sincronizar(); assertTrue(feed.lastId()>0)
                for(p in f.db.pedidosQueries.selectAll().executeAsList().filter { it.synced_at=="SYNCHRONIZED" }) {
                    assertNotNull(f.db.app_configQueries.getValue("pedidos_estados_evento_${p.id_pedido}").executeAsOneOrNull())
                }
            }
            Files.writeString(Path.of(env("PED_COM_SYNC_04_EVIDENCE")),JsonArray(evidence).toString())
            println("PED-COM-SYNC-04 real UAT: eight scenarios, PostgreSQL and feed verified")
            // Keep SQLite UAT evidence outside the repository; never delete real ERP orders.
            println("SQLite UAT evidence: $sqlite")
        } finally { pg.close(); loader.close(); client.close() }
    }
}
