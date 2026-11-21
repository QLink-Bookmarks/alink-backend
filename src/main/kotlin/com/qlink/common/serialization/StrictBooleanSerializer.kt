package com.qlink.common.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

internal object StrictBooleanSerializer : KSerializer<Boolean> {
    override val descriptor = Boolean.serializer().descriptor

    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder !is JsonDecoder) return decoder.decodeBoolean()

        val value = decoder.decodeJsonElement() as? JsonPrimitive
        return when (value?.takeUnless { it.isString }?.content) {
            "true" -> true
            "false" -> false
            else -> throw SerializationException("Expected a JSON boolean")
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Boolean,
    ) {
        encoder.encodeBoolean(value)
    }
}
