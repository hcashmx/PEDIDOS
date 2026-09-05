package com.cyssoftware.effyflow.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

lateinit var appContext: Context

fun initDatabase(context: Context) {
    appContext = context
}

actual fun createDriver(): SqlDriver {
//    val passphrase = SQLiteDatabase.getBytes("w5zKlNHX3Lh6ecOIk1Pl".toCharArray())
//    val factory = SupportFactory(passphrase)
    val driver = AndroidSqliteDriver(EffyDatabase.Schema, appContext, "effy.db"/*,  factory = factory*/)
    LocalSchemaMigrations.apply(driver)
    return driver
}
