package com.qlink.folder.route

import com.qlink.common.scroll.DEFAULT_SCROLL_SIZE
import io.ktor.resources.Resource

@Resource("/public-folders")
class PublicFolderResources {
    @Resource("{id}")
    class ById(
        val parent: PublicFolderResources = PublicFolderResources(),
        val id: String,
    ) {
        @Resource("links")
        class Links(
            val parent: ById,
            val cursor: String? = null,
            val size: Int = DEFAULT_SCROLL_SIZE,
            val orderKey: String? = null,
            val order: String? = null,
        )

        @Resource("todos")
        class Todos(
            val parent: ById,
            val cursor: String? = null,
            val size: Int = DEFAULT_SCROLL_SIZE,
            val orderKey: String? = null,
            val order: String? = null,
        )
    }
}
