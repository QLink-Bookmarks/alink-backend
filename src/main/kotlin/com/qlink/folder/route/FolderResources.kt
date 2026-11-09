package com.qlink.folder.route

import io.ktor.resources.Resource

@Resource("/folders")
class FolderResources(
    val query: String? = null,
    val orderKey: String? = null,
    val order: String? = null,
    val cursor: String? = null,
    val size: Int = 15,
) {
    @Resource("{id}")
    class ById(
        val parent: FolderResources = FolderResources(),
        val id: Long,
        val onDelete: String? = null,
        val targetFolderId: Long? = null,
    ) {
        @Resource("owner")
        class Owner(
            val parent: ById,
        )

        @Resource("members")
        class Members(
            val parent: FolderResources.ById,
        ) {
            @Resource("{memberId}")
            class MemberById(
                val parent: Members,
                val memberId: Long,
            )
        }
    }
}
