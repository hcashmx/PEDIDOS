package com.cyssoftware.effyflow.cotizacion.estados

import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.configureSyncBearer
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.SyncAuthenticationException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

fun cotizacionEstadosClient(
    getTokens: () -> Pair<String?, String?>,
    saveTokens: (String, String) -> Unit,
    refreshUrl: () -> String
) = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 20_000 }
    configureSyncBearer(getTokens, saveTokens, refreshUrl)
}

data class SyncOutcome(val state: String, val message: String)

class CotizacionEstadosSync(
    private val repository: CotizacionEstadosRepository,
    private val devices: DeviceIdentityRepository,
    private val api: CotizacionEstadosApi,
    private val userId: Long,
    private val agentId: Long,
    private val identityStillActive: () -> Boolean,
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()

    suspend fun synchronize(): SyncOutcome = mutex.withLock {
        requireIdentity()
        val device = devices.getOrCreate()
        var session = acquire(device)
        try {
            session = reconcileAck(session, device)
            if (session.remoteState in setOf("REVOCADA", "SESSION_EXPIRADA", "REQUIERE_REINICIO"))
                session = reset(session, device)
            if (session.bootstrapState != "ACTIVA") session = drainSnapshot(session, device)
            if (session.remoteState == "ACTIVA" && session.bootstrapState == "ACTIVA") drainEvents(session, device)
            val final = repository.latest(userId, agentId, device) ?: session
            SyncOutcome(final.remoteState, if (final.ingestionSuspended)
                "Cotizaciones actualizadas; Sync tiene la ingesta temporalmente saturada."
            else "Cotizaciones sincronizadas hasta ${final.committed}.")
        } catch (e: CotizacionEstadosHttpException) {
            handleHttp(session, e, device)
        } catch (e: StateIntegrityConflict) {
            repository.markResetRequired(session, "CONFLICTO_INTEGRIDAD", e.message ?: "Contradicción local")
            SyncOutcome("REQUIERE_REINICIO", "Se detectó una contradicción; no se confirmó la página.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncAuthenticationException) {
            repository.markError(session, "SYNC_AUTH_REQUIRED", "La sesión remota requiere autenticación.")
            SyncOutcome("REQUIERE_AUTENTICACION", "Inicia sesión nuevamente para sincronizar Cotizaciones.")
        } catch (e: Exception) {
            repository.markError(session, "RED_O_TIMEOUT", "No se confirmó la respuesta de Sync.")
            SyncOutcome("ERROR_TEMPORAL", "No se confirmó la respuesta de Sync; el checkpoint local se conserva.")
        }
    }

    suspend fun revalidate(): SyncOutcome = mutex.withLock {
        requireIdentity()
        val device = devices.getOrCreate()
        val s = repository.latest(userId, agentId, device) ?: acquire(device)
        try {
            val result = api.revalidate(s.sessionId, device)
            requireIdentity()
            repository.updateLease(s, result)
            SyncOutcome(result.state, "Autorización offline renovada.")
        } catch (e: CotizacionEstadosHttpException) { handleHttp(s, e, device) }
        catch (e: CancellationException) { throw e }
        catch (e: SyncAuthenticationException) {
            repository.markError(s, "SYNC_AUTH_REQUIRED", "La sesión remota requiere autenticación.")
            SyncOutcome("REQUIERE_AUTENTICACION", "Inicia sesión nuevamente para revalidar.")
        }
        catch (e: Exception) {
            repository.markError(s, "RED_O_TIMEOUT", "No se confirmó la revalidación.")
            SyncOutcome("ERROR_TEMPORAL", "No se confirmó la revalidación; el lease no se extendió.")
        }
    }

    private suspend fun acquire(device: String): EstadoSession {
        val existing = repository.latest(userId, agentId, device)
        if (existing != null) {
            try {
                val remote = api.state(existing.sessionId, device)
                requireIdentity()
                repository.reconcileState(existing, remote)
                return repository.find(existing.sessionId, existing.generation)!!
            } catch (e: CotizacionEstadosHttpException) {
                if (!e.unusableSession) throw e
                repository.revokeAndPurge(existing, e.code, "La sesión ya no es utilizable para esta identidad.")
            }
        }
        val opened = api.open(device)
        requireIdentity()
        return repository.persistSession(opened, userId, agentId, device)
    }

    private suspend fun reconcileAck(s: EstadoSession, device: String): EstadoSession {
        if (s.committed <= s.acked) return s
        val remote = api.state(s.sessionId, device)
        requireIdentity()
        repository.reconcileState(s, remote)
        var current = repository.find(s.sessionId, s.generation)!!
        if (current.acked < current.committed) {
            val ack = api.acknowledge(current.sessionId, device, current.committed)
            requireIdentity()
            repository.recordAck(current, ack)
            current = repository.find(s.sessionId, s.generation)!!
        }
        return current
    }

    private suspend fun drainSnapshot(initial: EstadoSession, device: String): EstadoSession {
        var s = initial
        var pendingAttempts = 0
        while (s.bootstrapState != "ACTIVA") {
            requireIdentity()
            val page = try { api.snapshot(s.sessionId, device, s.committed) }
            catch (e: CotizacionEstadosHttpException) {
                if (!e.snapshotPending) throw e
                if (++pendingAttempts > MAX_PENDING_POLLS) throw e
                wait(backoff(pendingAttempts, e.retryAfterSeconds))
                val state = api.state(s.sessionId, device)
                requireIdentity(); repository.reconcileState(s, state)
                s = repository.find(s.sessionId, s.generation)!!
                continue
            }
            requireIdentity()
            repository.applySnapshot(page, userId, agentId, device)
            s = repository.find(s.sessionId, s.generation)!!
            if (page.items.isNotEmpty()) s = acknowledge(s, device)
            if (!page.hasMore && s.bootstrapState != "ACTIVA") {
                val state = api.state(s.sessionId, device)
                requireIdentity(); repository.reconcileState(s, state)
                s = repository.find(s.sessionId, s.generation)!!
                if (s.remoteState == "ACTIVA") break
            }
        }
        return s
    }

    private suspend fun drainEvents(initial: EstadoSession, device: String) {
        var s = initial
        do {
            requireIdentity()
            val page = api.events(s.sessionId, device, s.committed)
            requireIdentity()
            repository.applyEvents(page, userId, agentId, device)
            s = repository.find(s.sessionId, s.generation)!!
            if (page.events.isNotEmpty()) s = acknowledge(s, device)
        } while (page.hasMore)
        val state = api.state(s.sessionId, device)
        requireIdentity(); repository.reconcileState(s, state)
    }

    private suspend fun acknowledge(s: EstadoSession, device: String): EstadoSession {
        try {
            val ack = api.acknowledge(s.sessionId, device, s.committed)
            requireIdentity(); repository.recordAck(s, ack)
        } catch (e: CotizacionEstadosHttpException) {
            if (!e.conflict) throw e
            val remote = api.state(s.sessionId, device)
            requireIdentity(); repository.reconcileState(s, remote)
            val reconciled = repository.find(s.sessionId, s.generation)!!
            if (reconciled.acked < reconciled.committed) throw e
        }
        return repository.find(s.sessionId, s.generation)!!
    }

    private suspend fun reset(old: EstadoSession, device: String): EstadoSession {
        val freshDto = api.reset(old.sessionId, device, old.generation)
        requireIdentity()
        require(freshDto.purgeRequired && freshDto.generation > old.generation && freshDto.sessionId != old.sessionId)
        repository.purgeNamespace(old)
        return repository.persistSession(freshDto, userId, agentId, device)
    }

    private suspend fun handleHttp(s: EstadoSession, e: CotizacionEstadosHttpException, device: String): SyncOutcome {
        return when {
            e.authorizationRevoked || e.unusableSession -> {
                repository.revokeAndPurge(s, e.code, e.safeMessage)
                SyncOutcome("REVOCADA", "La autorización fue revocada; se retiraron los datos sincronizados.")
            }
            e.resetRequired -> {
                repository.markResetRequired(s, e.code, e.safeMessage)
                val fresh = reset(repository.find(s.sessionId, s.generation)!!, device)
                val done = drainSnapshot(fresh, device)
                SyncOutcome(done.remoteState, "La generación fue reiniciada y reconstruida.")
            }
            e.conflict -> {
                val remote = api.state(s.sessionId, device)
                requireIdentity(); repository.reconcileState(s, remote)
                repository.markError(repository.find(s.sessionId, s.generation)!!, e.code, e.safeMessage)
                SyncOutcome("CONFLICTO", "Se consultó el estado remoto sin avanzar el cursor local.")
            }
            e.retryable || e.snapshotPending -> {
                repository.markError(s, e.code, e.safeMessage)
                SyncOutcome("ERROR_TEMPORAL", e.safeMessage)
            }
            else -> {
                repository.markError(s, e.code, e.safeMessage)
                SyncOutcome("ERROR", e.safeMessage)
            }
        }
    }

    private fun requireIdentity() {
        if (!identityStillActive()) throw CancellationException("La identidad local cambió durante la sincronización.")
    }
    private fun backoff(attempt: Int, retryAfter: Long?): Long =
        ((retryAfter ?: minOf(1L shl attempt.coerceAtMost(5), 30L)) * 1_000L).coerceAtMost(30_000L)

    companion object { private const val MAX_PENDING_POLLS = 5 }
}
