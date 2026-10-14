package com.cyssoftware.effyflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cyssoftware.effyflow.cotizacion.estados.DeviceIdentityRepository
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.db.LocalSchemaMigrations
import kotlinx.coroutines.*
import kotlin.test.*

class DeviceIdentityRepositoryTest {
    private fun fixture(): Pair<JdbcSqliteDriver,EffyDatabase> {
        val d=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); EffyDatabase.Schema.create(d); LocalSchemaMigrations.apply(d)
        return d to EffyDatabase(d)
    }
    @Test fun migratesDesktopUuidWithoutChangingLegacyOrFrozenPayload() = runBlocking {
        val (driver,db)=fixture(); try {
            val uuid="c0a8012e-8a2d-4c9c-aaf4-9a8f58a1f9b2"
            val legacy="desktop-$uuid"
            db.app_configQueries.upsertValue(DeviceIdentityRepository.LEGACY_KEY,legacy)
            val frozen="{\"dispositivoId\":\"$legacy\",\"uuid\":\"frozen\"}"
            db.app_configQueries.upsertValue("frozen_payload_test",frozen)
            assertEquals(uuid,DeviceIdentityRepository(db).getOrCreate())
            assertEquals(legacy,db.app_configQueries.getValue(DeviceIdentityRepository.LEGACY_KEY).executeAsOne())
            assertEquals(frozen,db.app_configQueries.getValue("frozen_payload_test").executeAsOne())
        } finally { driver.close() }
    }
    @Test fun initializationIsStableAcrossRestartsAndConcurrentCalls() = runBlocking {
        val (driver,db)=fixture(); try {
            val values=coroutineScope { (1..8).map { async(Dispatchers.Default) { DeviceIdentityRepository(db).getOrCreate() } }.awaitAll() }
            assertEquals(1,values.toSet().size)
            assertEquals(values.first(),DeviceIdentityRepository(db).getOrCreate())
            assertTrue(Regex("^[0-9a-f-]{36}$").matches(values.first()))
        } finally { driver.close() }
    }

    @Test fun existingDatabaseMigrationCreatesStateTablesWithoutChangingCommercialRows() {
        val (driver,db)=fixture(); try {
            driver.execute(null,"DROP TABLE cot_estado_delivery_receipt",0)
            driver.execute(null,"DROP TABLE cot_estado_tombstone",0)
            driver.execute(null,"DROP TABLE cot_estado_document",0)
            driver.execute(null,"DROP TABLE cot_estado_sync_session",0)
            db.app_configQueries.upsertValue("commercial_sentinel","unchanged")
            LocalSchemaMigrations.apply(driver)
            val exists=driver.executeQuery(null,
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name LIKE 'cot_estado_%'",
                { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)) },0).value
            assertEquals(4,exists)
            assertEquals("unchanged",db.app_configQueries.getValue("commercial_sentinel").executeAsOne())
        } finally { driver.close() }
    }
}
