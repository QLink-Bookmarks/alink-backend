package com.qlink.common.docs

import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Schema

fun RouteConfig.optionalReadAuth() {
    protected = false
    extensions(mapOf("x-optional-auth" to true))
}

fun readResponseSchema(resource: String): Schema<*> =
    ComposedSchema().anyOf(
        listOf(
            Schema<Any>().`$ref`("#/components/schemas/Member$resource"),
            Schema<Any>().`$ref`("#/components/schemas/Guest$resource"),
        ),
    )
