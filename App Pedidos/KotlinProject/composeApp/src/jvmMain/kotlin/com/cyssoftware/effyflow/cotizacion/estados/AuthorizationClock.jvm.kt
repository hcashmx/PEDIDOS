package com.cyssoftware.effyflow.cotizacion.estados

internal actual fun platformElapsedRealtimeMillis(): Long = System.nanoTime() / 1_000_000L
