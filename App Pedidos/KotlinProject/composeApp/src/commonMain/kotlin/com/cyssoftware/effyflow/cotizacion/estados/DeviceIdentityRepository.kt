package com.cyssoftware.effyflow.cotizacion.estados

import com.benasher44.uuid.uuid4
import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.db.EffyDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DeviceIdentityRepository(private val db: EffyDatabase = DatabaseProvider.database) {
    suspend fun getOrCreate(): String = initializationMutex.withLock { db.transactionWithResult {
        canonical(db.app_configQueries.getValue(KEY).executeAsOneOrNull())?.let { return@transactionWithResult it }
        val legacy = db.app_configQueries.getValue(LEGACY_KEY).executeAsOneOrNull()
        val normalized = canonical(legacy) ?: legacy?.removePrefix("desktop-")?.let(::canonical)
            ?: uuid4().toString().lowercase()
        // Deliberately do not modify LEGACY_KEY: frozen capture payloads and future capture behavior stay intact.
        db.app_configQueries.insertValueIfAbsent(KEY, normalized)
        canonical(db.app_configQueries.getValue(KEY).executeAsOne())
            ?: error("No se pudo persistir un UUID canónico de dispositivo.")
    } }

    private fun canonical(value: String?): String? {
        val candidate = value?.trim() ?: return null
        if (!CANONICAL.matches(candidate)) return null
        return candidate.lowercase()
    }

    companion object {
        const val KEY = "sync_device_id"
        const val LEGACY_KEY = "cotizaciones_dispositivo_id"
        private val CANONICAL = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        private val initializationMutex = Mutex()
    }
}
