package com.qlink.common.response

sealed interface ReadResult<out Member, out Guest> {
    data class Member<out T>(
        val data: T,
    ) : ReadResult<T, Nothing>

    data class Guest<out T>(
        val data: T,
    ) : ReadResult<Nothing, T>
}
