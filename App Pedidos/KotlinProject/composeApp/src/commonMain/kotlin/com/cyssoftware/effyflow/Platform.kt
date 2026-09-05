package com.cyssoftware.effyflow

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform