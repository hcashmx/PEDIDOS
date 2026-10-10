package com.cyssoftware.effyflow.login.data

import com.cyssoftware.effyflow.db.DatabaseProvider
import com.cyssoftware.effyflow.db.EffyDatabase
import com.cyssoftware.effyflow.login.data.model.AuthorizedUser
import com.cyssoftware.effyflow.login.data.model.LoginResponse
import kotlinx.datetime.Clock

class AuthLocalDataSource(val db: EffyDatabase = DatabaseProvider.database) {
    private val queries = db.authQueries
    fun hasCatalog() = db.app_configQueries.getValue("offline_users_catalog_ready").executeAsOneOrNull() == "1"
    fun users() = queries.allUsers().executeAsList()
    fun findUser(username: String) = queries.findUser(username.trim()).executeAsOneOrNull()

    /** Apply only complete snapshots; absence, disablement and credential changes revoke access. */
    fun applyCatalog(users: List<AuthorizedUser>) {
        require(users.map { it.id }.distinct().size == users.size && users.map { it.username }.distinct().size == users.size)
        require(users.all { it.id > 0 && it.username.isNotBlank() && it.credentialVersion > 0 && it.roles in APP_ROLES && it.appAuthorized })
        db.transaction {
            val previous = queries.allUsers().executeAsList().associateBy { it.userId }
            require(users.all { it.credentialVersion >= (previous[it.id]?.credentialVersion ?: 0L) }) {
                "La versión de credencial del servidor retrocedió. Reintenta sincronizar."
            }
            queries.disableAll()
            users.forEach { user ->
                val old = previous[user.id]
                if (old != null && (old.credentialVersion != user.credentialVersion || !user.active)) {
                    println(if (!user.active) "USER_DISABLED" else "CREDENTIAL_VERSION_CHANGED")
                    queries.invalidateUser(user.id); queries.deleteTokens(user.id)
                }
                queries.insertUser(user.id, user.username, user.email, user.nombre, user.roles,
                    if (user.active) 1L else 0L, user.credentialVersion)
                queries.updateUser(user.username, user.email, user.nombre, user.roles,
                    if (user.active) 1L else 0L, user.credentialVersion, user.id)
                // Existing Pedido capture resolves its user from the identity catalog, never credentials.
                db.usersQueries.insertUsers(user.id, user.nombre, user.email, null, user.roles,
                    if (user.active) "ACTIVO" else "INACTIVO", null)
            }
            queries.allUsers().executeAsList().filter { it.active == 0L }.forEach {
                queries.invalidateUser(it.userId); queries.deleteTokens(it.userId)
            }
            if (queries.getSession().executeAsOneOrNull() == null) queries.clearSession()
            db.app_configQueries.upsertValue("offline_users_catalog_ready", "1")
        }
    }

    fun enroll(username: String, password: String, response: LoginResponse) {
        val user = checkNotNull(findUser(username)) { "Sincroniza usuarios antes de enrolar." }
        require(user.active == 1L && response.active == true && response.appAuthorized == true)
        require(response.usuarioId?.toLong() == user.userId && response.username == user.username)
        require(response.credentialVersion == user.credentialVersion && response.roles == user.roles) { "El catálogo cambió. Sincroniza usuarios nuevamente." }
        require(!response.accessToken.isNullOrBlank() && !response.refreshToken.isNullOrBlank())
        val encoded = PasswordVerifier.create(password)
        db.transaction {
            // Recheck after KDF: concurrent sync must not reinstate revoked authorization.
            check(queries.findUserById(user.userId).executeAsOne() == user) { "El catálogo cambió. Reintenta." }
            queries.enrollUser(encoded.salt, encoded.verifier, encoded.iterations.toLong(), Clock.System.now().toString(), user.userId)
            queries.saveTokens(user.userId, response.accessToken, response.refreshToken)
            openSession(user.userId)
        }
    }

    fun loginLocally(username: String, password: String): LoginResponse {
        val user = findUser(username)
        if (user == null || user.active != 1L || user.verifier == null || user.salt == null || user.iterations == null ||
            !PasswordVerifier.matches(password, user.salt, user.verifier, user.iterations.toInt())) {
            println("LOCAL_LOGIN_FAILURE")
            return LoginResponse(error = "Usuario o contraseña incorrectos, o usuario deshabilitado.")
        }
        return db.transactionWithResult {
            if (queries.findUserById(user.userId).executeAsOne() != user) {
                LoginResponse(error = "La autorización cambió. Reintenta iniciar sesión.")
            } else {
                openSession(user.userId)
                println("LOCAL_LOGIN_SUCCESS")
                checkNotNull(getSession())
            }
        }
    }

    private fun openSession(userId: Long) {
        queries.openSession(userId, Clock.System.now().toString())
        db.app_configQueries.upsertValue("last_login_username", queries.findUserById(userId).executeAsOne().username)
    }
    fun saveRemoteTokens(userId: Long, accessToken: String, refreshToken: String) {
        require(accessToken.isNotBlank() && refreshToken.isNotBlank())
        queries.saveTokens(userId, accessToken, refreshToken)
    }
    fun updateTokens(accessToken: String, refreshToken: String) {
        val session = checkNotNull(getSession()) { "No active local session to refresh" }
        updateTokensForUser(checkNotNull(session.usuarioId).toLong(), accessToken, refreshToken)
    }
    fun updateTokensForUser(userId: Long, accessToken: String, refreshToken: String) {
        require(accessToken.isNotBlank() && refreshToken.isNotBlank()) { "Tokens must not be blank" }
        db.transaction {
            val session = checkNotNull(queries.getSession().executeAsOneOrNull()) { "No active local session to refresh" }
            check(session.usuarioId == userId) { "Local user changed during remote token refresh" }
            queries.saveTokens(userId, accessToken, refreshToken)
        }
    }
    fun getSession(): LoginResponse? = queries.getSession().executeAsOneOrNull()?.let {
        LoginResponse(roles=it.roles, mensaje="Sesión local iniciada", accessToken=it.accessToken,
            usuarioId=it.usuarioId.toInt(), nombre=it.usuarioName, refreshToken=it.refreshToken, username=it.email)
    }
    fun getTokens(): Pair<String?, String?> = getSession().let { it?.accessToken to it?.refreshToken }
    fun getCachedEmail() = db.app_configQueries.getValue("last_login_username").executeAsOneOrNull()
    fun clearSession() { queries.clearSession() }
    companion object { val APP_ROLES = setOf("AGENTE", "VENDEDOR", "ADMIN") }
}
