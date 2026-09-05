package com.cyssoftware.effyflow.db

import app.cash.sqldelight.db.SqlDriver

object LocalSchemaMigrations {
    fun apply(driver: SqlDriver) {
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
    }
}
