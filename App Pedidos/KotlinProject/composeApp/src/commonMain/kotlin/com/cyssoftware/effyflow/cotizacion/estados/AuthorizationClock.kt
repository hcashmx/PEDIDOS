package com.cyssoftware.effyflow.cotizacion.estados

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

interface AuthorizationClock {
    fun wallNow(): Instant
    fun elapsedRealtimeMillis(): Long
}

class PlatformAuthorizationClock : AuthorizationClock {
    override fun wallNow(): Instant = Clock.System.now()
    override fun elapsedRealtimeMillis(): Long = platformElapsedRealtimeMillis()
}

internal expect fun platformElapsedRealtimeMillis(): Long
