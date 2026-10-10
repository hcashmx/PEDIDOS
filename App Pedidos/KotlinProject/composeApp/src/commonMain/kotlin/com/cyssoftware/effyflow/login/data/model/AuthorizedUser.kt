package com.cyssoftware.effyflow.login.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AuthorizedUser(
    val id: Long, val username: String, val nombre: String, val email: String? = null,
    val roles: String, val active: Boolean, val appAuthorized: Boolean, val credentialVersion: Long
)
@Serializable
data class AuthorizedUserBatch(val page: Int, val totalPages: Int, val totalElements: Long, val data: List<AuthorizedUser>)
