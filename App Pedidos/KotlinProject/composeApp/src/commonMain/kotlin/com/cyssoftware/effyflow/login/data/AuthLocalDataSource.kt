package com.cyssoftware.effyflow.login.data
import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.login.data.model.LoginResponse
class AuthLocalDataSource() {
    val db= DatabaseProvider.database
    fun saveSession(email: String, pass: String, data: LoginResponse) {



        db.authQueries.insertOrReplaceSession(
            email = email,
            password = pass,
            accessToken = data.accessToken ?: "",
            refreshToken = data.refreshToken,
            roles = data.roles,
            usuarioName = data.nombre,
            usuarioId = data.usuarioId?.toLong() // SQLDelight usa Long para INTEGER
        )
    }

    fun getSession(): LoginResponse? {
        val cached = db.authQueries.getSession().executeAsOneOrNull() ?: return null

        return LoginResponse(
            roles = cached.roles,
            mensaje = "Iniciado en modo Offline", // Indicador visual opcional
            accessToken = cached.accessToken,
            usuarioId = cached.usuarioId?.toInt(), // Casteo de vuelta a Int
            nombre = cached.usuarioName,
            refreshToken = cached.refreshToken,
            error = null
        )
    }

    fun validateCredentials(email: String, pass: String): Boolean {
        val cached = db.authQueries.getSession().executeAsOneOrNull() ?: return false
        return cached.email == email && cached.password == pass
    }

    fun getCachedEmail(): String? {
        // Consulta directa a la tabla UserSession creada anteriormente
        return db.authQueries.getSession().executeAsOneOrNull()?.email
    }

    fun clearSession() {
        db.authQueries.clearSession()
    }
}