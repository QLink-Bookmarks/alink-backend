package com.qlink.common.response

import io.ktor.http.HttpHeaders
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.header

val ReadNoStore =
    createRouteScopedPlugin("ReadNoStore") {
        onCall { call ->
            call.response.header(HttpHeaders.CacheControl, "no-store")
        }
    }
