package com.cyssoftware.effyflow.db

import app.cash.sqldelight.db.SqlDriver

expect fun createDriver(): SqlDriver


object DatabaseProvider {
    private val driver: SqlDriver by lazy { createDriver() }
    val database: EffyDatabase by lazy { EffyDatabase(driver) }
}