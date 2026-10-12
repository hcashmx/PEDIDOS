package com.cyssoftware.effyflow.db

import app.cash.sqldelight.db.SqlDriver

object LocalSchemaMigrations {
    fun apply(driver: SqlDriver) {
        // secure_delete scrubs the discarded plaintext cache, including legacy users.pass.
        driver.execute(null, "PRAGMA secure_delete = ON", 0)
        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS presentaciones_producto (
                id_presentacion INTEGER NOT NULL PRIMARY KEY,
                id_producto INTEGER NOT NULL,
                id_unidad INTEGER NOT NULL,
                descripcion TEXT,
                factor_conversion REAL,
                cantidad_base REAL,
                es_unidad_venta INTEGER,
                es_predeterminada INTEGER,
                updated_at TEXT
            )
            """.trimIndent(),
            0
        )

        try {
            driver.execute(null, "ALTER TABLE impuestos ADD COLUMN signo INTEGER", 0)
        } catch (_: Exception) {
            // Existing databases already migrated have this column.
        }

        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS app_config (
                key TEXT NOT NULL PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
            0
        )

        listOf(
            "ALTER TABLE clientes ADD COLUMN credito_bloqueado INTEGER",
            "ALTER TABLE clientes ADD COLUMN motivo_bloqueo_credito TEXT",
            "ALTER TABLE clientes ADD COLUMN fecha_bloqueo_credito TEXT",
            "ALTER TABLE series_folios ADD COLUMN es_consecutiva INTEGER"
        ).forEach { statement ->
            try {
                driver.execute(null, statement, 0)
            } catch (_: Exception) {
                // Existing databases already migrated have this column.
            }
        }

        listOf(
            "ALTER TABLE pedidos ADD COLUMN tipo_venta TEXT",
            "ALTER TABLE pedidos ADD COLUMN payload_comercial TEXT",
            "ALTER TABLE pedidos ADD COLUMN resultado_comercial TEXT",
            "ALTER TABLE pedidos ADD COLUMN total_erp TEXT",
            "ALTER TABLE pedidos ADD COLUMN serie_erp TEXT",
            "ALTER TABLE pedidos ADD COLUMN fecha_recepcion TEXT",
            "ALTER TABLE pedidos ADD COLUMN version_erp TEXT",
            "ALTER TABLE pedidos ADD COLUMN estado_erp TEXT"
        ).forEach { statement ->
            try {
                driver.execute(null, statement, 0)
            } catch (e: Exception) {
                // Never hide a failed commercial outbox migration (disk full, locks, etc.).
                if (e.message?.contains("duplicate column name", ignoreCase = true) != true) throw e
            }
        }
        driver.execute(null, "CREATE TABLE IF NOT EXISTS pedido_estado_pendiente (historial_id INTEGER NOT NULL PRIMARY KEY, remote_id INTEGER NOT NULL, uuid_local TEXT, estado_id INTEGER NOT NULL, estado TEXT NOT NULL, fecha_cambio TEXT NOT NULL)", 0)
        driver.execute(null, """CREATE TABLE IF NOT EXISTS revisiones_comerciales (
            revision_id TEXT NOT NULL PRIMARY KEY, version_esquema INTEGER NOT NULL,
            publicada_en TEXT NOT NULL, referencia_comercial TEXT NOT NULL,
            huella_sha256 TEXT NOT NULL, datos_json TEXT NOT NULL, descargada_en TEXT NOT NULL)""", 0)
        driver.execute(null, """CREATE TABLE IF NOT EXISTS cotizaciones (
            uuid_local TEXT NOT NULL PRIMARY KEY, usuario_autor_id INTEGER NOT NULL,
            agente_id INTEGER NOT NULL, cliente_id INTEGER NOT NULL, fecha_comercial TEXT NOT NULL,
            capturada_en TEXT NOT NULL, revision_comercial_id TEXT NOT NULL,
            revision_huella_sha256 TEXT NOT NULL,
            documento_solicitado TEXT NOT NULL CHECK (documento_solicitado IN ('FACTURA','REMISION')),
            observaciones TEXT, estado_local TEXT NOT NULL, estado_comercial_erp TEXT,
            modificado_en TEXT NOT NULL, id_erp INTEGER, version_erp INTEGER,
            subtotal_referencial TEXT NOT NULL DEFAULT '0')""", 0)
        listOf(
            "ALTER TABLE cotizaciones ADD COLUMN subtotal_erp TEXT",
            "ALTER TABLE cotizaciones ADD COLUMN impuestos_erp TEXT",
            "ALTER TABLE cotizaciones ADD COLUMN total_erp TEXT"
        ).forEach { statement ->
            try { driver.execute(null, statement, 0) }
            catch (e: Exception) { if (e.message?.contains("duplicate column name", ignoreCase = true) != true) throw e }
        }
        driver.execute(null, """CREATE TABLE IF NOT EXISTS cotizacion_renglones (
            uuid_local TEXT NOT NULL, numero INTEGER NOT NULL, producto_id INTEGER NOT NULL,
            presentacion_id INTEGER, cantidad TEXT NOT NULL, precio_referencia TEXT NOT NULL,
            descuento_solicitado TEXT NOT NULL DEFAULT '0', PRIMARY KEY (uuid_local,numero),
            FOREIGN KEY (uuid_local) REFERENCES cotizaciones(uuid_local) ON DELETE CASCADE)""", 0)
        listOf(
            "ALTER TABLE cotizacion_renglones ADD COLUMN revision_comercial_id TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE cotizacion_renglones ADD COLUMN revision_huella_sha256 TEXT NOT NULL DEFAULT ''"
        ).forEach { statement ->
            try { driver.execute(null, statement, 0) }
            catch (e: Exception) { if (e.message?.contains("duplicate column name", ignoreCase = true) != true) throw e }
        }
        // Backfill lineage only; preserve the originally captured price and discount byte-for-byte.
        driver.execute(null, """UPDATE cotizacion_renglones SET
            revision_comercial_id = (SELECT revision_comercial_id FROM cotizaciones WHERE cotizaciones.uuid_local=cotizacion_renglones.uuid_local),
            revision_huella_sha256 = (SELECT revision_huella_sha256 FROM cotizaciones WHERE cotizaciones.uuid_local=cotizacion_renglones.uuid_local)
            WHERE (revision_comercial_id='' OR revision_huella_sha256='')
            AND EXISTS (SELECT 1 FROM cotizaciones WHERE cotizaciones.uuid_local=cotizacion_renglones.uuid_local)""", 0)
        driver.execute(null, """CREATE TABLE IF NOT EXISTS cotizacion_outbox (
            uuid_local TEXT NOT NULL PRIMARY KEY, payload_json TEXT NOT NULL, estado_envio TEXT NOT NULL,
            intentos INTEGER NOT NULL DEFAULT 0, ultimo_error_codigo TEXT, ultimo_error TEXT,
            ultimo_intento_en TEXT, proximo_intento_en TEXT, resultado_envio TEXT, respuesta_json TEXT,
            FOREIGN KEY (uuid_local) REFERENCES cotizaciones(uuid_local) ON DELETE CASCADE)""", 0)
        migrateOfflineAuthentication(driver)
    }

    private fun migrateOfflineAuthentication(driver: SqlDriver) {
        val legacy = driver.executeQuery(null,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='UserSession'",
            { cursor -> cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)!! > 0) }, 0).value
        driver.execute(null, "CREATE TABLE IF NOT EXISTS OfflineUser (userId INTEGER NOT NULL PRIMARY KEY, username TEXT NOT NULL UNIQUE, email TEXT, nombre TEXT NOT NULL, roles TEXT NOT NULL, active INTEGER NOT NULL, credentialVersion INTEGER NOT NULL, salt TEXT, verifier TEXT, iterations INTEGER, enrolledAt TEXT)", 0)
        driver.execute(null, "CREATE TABLE IF NOT EXISTS LocalSession (id INTEGER NOT NULL PRIMARY KEY CHECK (id=1), userId INTEGER NOT NULL, authenticatedAt TEXT NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE IF NOT EXISTS SyncCredentials (userId INTEGER NOT NULL PRIMARY KEY, accessToken TEXT NOT NULL, refreshToken TEXT NOT NULL)", 0)
        // Never derive offline authorization from the old cache. Require fresh online enrollment.
        driver.execute(null, "UPDATE users SET pass=NULL WHERE pass IS NOT NULL", 0)
        driver.execute(null, "DROP TABLE IF EXISTS UserSession", 0)
        if (legacy) {
            driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0)
            driver.execute(null, "VACUUM", 0)
        }
    }
}
