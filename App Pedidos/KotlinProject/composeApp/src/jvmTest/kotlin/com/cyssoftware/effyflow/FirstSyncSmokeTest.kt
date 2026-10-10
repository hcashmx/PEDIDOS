package com.cyssoftware.effyflow

import com.cyssoftware.effyflow.login.data.AuthLocalDataSource
import com.cyssoftware.effyflow.login.data.LoginRepository
import com.cyssoftware.effyflow.pedido.sincronizacion.viewModel.SyncViewModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FirstSyncSmokeTest {

    @Test
    fun loginAndRunTwoCatalogSyncs() = runBlocking {
        val email = System.getenv("EFFY_TEST_EMAIL") ?: error("EFFY_TEST_EMAIL is required")
        val password = System.getenv("EFFY_TEST_PASSWORD") ?: error("EFFY_TEST_PASSWORD is required")

        val login = LoginRepository().login(email, password)
        assertFalse(login.accessToken.isNullOrBlank(), "Login did not return an access token")

        val local = AuthLocalDataSource()
        val session = local.getSession()
        assertNotNull(session, "UserSession was not saved")

        runSync("FIRST", local)
        runSync("SECOND", local)
    }

    private fun runSync(label: String, local: AuthLocalDataSource) {
        val latch = CountDownLatch(1)
        val capture = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err

        val sync = SyncViewModel(
            getToken = {
                val session = local.getSession()
                Pair(session?.accessToken, session?.refreshToken)
            },
            saveTokens = { accessToken, refreshToken ->
                val session = local.getSession()
                if (session != null) {
                    local.updateTokens(accessToken, refreshToken)
                }
            },
            onSyncComplete = { latch.countDown() }
        )

        System.setOut(PrintStream(capture, true, Charsets.UTF_8))
        System.setErr(PrintStream(capture, true, Charsets.UTF_8))
        try {
            sync.syncAllCatalogs()
            if (!latch.await(8, TimeUnit.MINUTES)) {
                error("$label sync did not finish in time")
            }
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }

        val log = capture.toString(Charsets.UTF_8)
        val errorLines = log.lineSequence().count { it.contains("Error", ignoreCase = true) }
        val pages = log.lineSequence().count { it.contains("Cat", ignoreCase = true) && it.contains("gina", ignoreCase = true) }
        val records = log.lineSequence()
            .filter { it.contains("Registros obtenidos", ignoreCase = true) }
            .mapNotNull { it.substringAfterLast(":").trim().toIntOrNull() }
            .sum()

        println("SYNC_SUMMARY|$label|pages=$pages|records=$records|errors=$errorLines")
        if (errorLines > 0) {
            error("$label sync produced $errorLines error log lines")
        }
    }
}
