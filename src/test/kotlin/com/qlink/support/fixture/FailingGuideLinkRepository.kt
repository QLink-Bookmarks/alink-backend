package com.qlink.support.fixture

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.link.domain.Link
import com.qlink.link.repository.LinkRepository

class FailingGuideLinkRepository(
    private val delegate: LinkRepository,
) : LinkRepository by delegate {
    val inserted = mutableListOf<Link>()

    override suspend fun insert(link: Link): Link {
        if (inserted.isNotEmpty()) throw BusinessException(ErrorCode.COMMON_INTERNAL_SERVER_ERROR)
        return delegate.insert(link).also { inserted += it }
    }
}
