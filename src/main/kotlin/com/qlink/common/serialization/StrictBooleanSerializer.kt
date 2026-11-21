package com.qlink.common.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

internal object StrictBooleanSerializer : JsonTransformingSerializer<Boolean>(Boolean.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element is JsonPrimitive && element.isString) {
            throw SerializationException("Expected a JSON boolean")
        }
        return element
    }
}
