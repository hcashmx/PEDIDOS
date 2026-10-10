package com.cyssoftware.effyflow.login.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(
    val roles: String? = null,
    val mensaje: String? = null,
    val accessToken: String? = null,
    val usuarioId: Int? = null,
    val nombre: String? = null,
    val refreshToken: String? = null,
    val error: String? = null,
    val username: String? = null,
    val active: Boolean? = null,
    val appAuthorized: Boolean? = null,
    val credentialVersion: Long? = null
)
