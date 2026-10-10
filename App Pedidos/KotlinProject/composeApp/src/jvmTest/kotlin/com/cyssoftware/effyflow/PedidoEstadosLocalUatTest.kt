package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.login.data.AuthLocalDataSource
import com.cyssoftware.effyflow.pedido.nuevoPedido.PedidoRepository
import com.cyssoftware.effyflow.pedido.nuevoPedido.viewmodel.*
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*

/** Explicit opt-in: another Kotlin capture for the AUTORIZADO → CANCELADO UAT branch. */
class PedidoEstadosLocalUatTest {
    @Test fun captureCancellationBranchThroughKotlinAndSync() = runBlocking<Unit> {
        val setting = System.getenv("PED_R1_UAT_DB") ?: return@runBlocking
        val file = Path.of(setting).toAbsolutePath().normalize()
        require(file.parent.parent.fileName.toString().startsWith("PED-SYNC-R1-05-"))
        val driver = JdbcSqliteDriver("jdbc:sqlite:$file")
        val db = EffyDatabase(driver)
        val local = AuthLocalDataSource(db)
        val repo = PedidoRepository(db)
        val client = HttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            configureSyncBearer(local::getTokens, local::updateTokens) { "http://localhost:8083/api/auth/refresh" }
        }
        try {
            val source = db.pedidosQueries.findByRemoteId("116").executeAsOne()
            assertEquals(source.usuario_creo_id, local.getSession()?.usuarioId?.toLong())
            val lines = db.detalle_pedidosQueries.getDetallesPorPedido(source.id_pedido).executeAsList().map { row ->
                val fiscal = Json.parseToJsonElement(assertNotNull(row.impuestos)).jsonObject
                val product = db.productosQueries.findById(row.producto_id).executeAsOne()
                PedidoLinea(repo.searchProductosConDetalle(product.codigo!!).first { it.id_producto == row.producto_id },
                    row.cantidad_solicitada, row.precio_unitario!!, row.descuento!!,
                    fiscal["subtotal"]!!.jsonPrimitive.double,
                    fiscal["impuestos"]!!.jsonArray.map { element ->
                        val tax = element.jsonObject
                        ImpuestoCalculado(tax["idImpuesto"]!!.jsonPrimitive.long, tax["impuesto"]!!.jsonPrimitive.content,
                            tax["tipoAplicacion"]!!.jsonPrimitive.content, tax["tipoFactor"]!!.jsonPrimitive.content,
                            tax["tasa"]!!.jsonPrimitive.double, tax["base"]!!.jsonPrimitive.double,
                            tax["importe"]!!.jsonPrimitive.double, tax["cImpuesto"]!!.jsonPrimitive.content,
                            tax["signo"]!!.jsonPrimitive.long)
                    }, row.total_linea!!, row.presentacion_id, row.almacen_id)
            }
            val previousBranch = db.pedidosQueries.selectAll().executeAsList().firstOrNull {
                it.usuario_creo_id == source.usuario_creo_id && it.observaciones == "PED-SYNC-R1-05 UAT rama cancelación"
            }
            val id = previousBranch?.id_pedido ?: assertNotNull(repo.guardarPedidoLocal(
                db.agentesQueries.findById(source.agente_ventas_id).executeAsOne(),
                db.usersQueries.findById(source.usuario_creo_id).executeAsOne(),
                db.clientesQueries.findById(source.cliente_id).executeAsOne(),
                db.series_foliosQueries.selectAll().executeAsList().first { it.id_serie == source.serie_id },
                (db.pedidosQueries.getMaxFolioPorSerie(source.serie_id).executeAsOne().max_folio ?: 0) + 1,
                lines, 0, "PED-SYNC-R1-05 UAT rama cancelación", TipoDocumento.FACTURA, null, TipoPedido.CONTADO))
            val captured = db.pedidosQueries.findById(id).executeAsOne()
            assertNotEquals(source.uuid_local, captured.uuid_local)
            java.time.OffsetDateTime.parse(captured.fecha_creacion)
            db.app_configQueries.upsertValue("r1_05_cancel_branch_local", id.toString())
            // An explicit UAT rerun is a manual retry after restoring the ERP service session.
            // It must reuse the capture/UUID/payload saved by the first attempt.
            if (captured.synced_at == "ERROR_NO_REINTENTABLE" || captured.synced_at == "ERROR_REINTENTABLE")
                db.pedidosQueries.retryRevision(id)
            PedidoComercialSync(db).enviar(id) { payload ->
                val response = client.post("http://localhost:8083/api/sync/pedidos") {
                    contentType(ContentType.Application.Json); setBody(payload)
                }
                response.status.value to response.body<JsonObject>()
            }
            val accepted = db.pedidosQueries.findById(id).executeAsOne()
            assertEquals("SYNCHRONIZED", accepted.synced_at)
            assertEquals(captured.uuid_local, accepted.uuid_local)
            assertEquals(captured.fecha_creacion, accepted.fecha_creacion)
            db.app_configQueries.upsertValue("r1_05_cancel_branch_local", id.toString())
            db.app_configQueries.upsertValue("r1_05_cancel_branch_remote", assertNotNull(accepted.uuid_server))
            println("R1_UAT_BRANCH_LOCAL=$id; ERP=${accepted.uuid_server}; UUID=${accepted.uuid_local}")
        } finally {
            client.close(); driver.getConnection().close(); driver.close()
        }
    }
}
