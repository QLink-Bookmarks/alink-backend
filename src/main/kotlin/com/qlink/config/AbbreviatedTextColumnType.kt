package com.qlink.config

import org.jetbrains.exposed.v1.core.TextColumnType

class AbbreviatedTextColumnType(
    private val logLimit: Int,
) : TextColumnType() {
    fun abbreviate(value: String): String {
        val length = value.codePointCount(0, value.length)
        if (length <= logLimit) return value

        val prefixEnd = value.offsetByCodePoints(0, logLimit)
        return "${value.substring(0, prefixEnd)}…(total_length=$length)"
    }
}
