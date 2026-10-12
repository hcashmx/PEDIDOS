package com.cyssoftware.effyflow.cotizacion

actual fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
