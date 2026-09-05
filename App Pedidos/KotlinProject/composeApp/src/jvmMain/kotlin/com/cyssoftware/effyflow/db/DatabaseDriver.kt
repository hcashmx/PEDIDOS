package com.cyssoftware.effyflow.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual fun createDriver(): SqlDriver {
    val baseDir = platformDataDirectory()

    if (!baseDir.exists()) baseDir.mkdirs()
    val dbFile = File(baseDir, "effy.db")
    val shouldCreateSchema = !dbFile.exists()

    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")

   // driver.execute(null, "PRAGMA key = 'w5zKlNHX3Lh6ecOIk1Pl';", 0)

    if (shouldCreateSchema) EffyDatabase.Schema.create(driver)
    LocalSchemaMigrations.apply(driver)

    return driver
}

private fun platformDataDirectory(): File {
    val osName = System.getProperty("os.name")
    return when {
        osName.startsWith("Windows") -> {
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: File(System.getProperty("user.home"), "AppData/Local").absolutePath
            File(localAppData, "EffyFlow")
        }
        osName.startsWith("Mac") ->
            File(System.getProperty("user.home"), "Library/Application Support/EffyFlow")
        else ->
            File(System.getProperty("user.home"), ".local/share/EffyFlow")
    }
}
