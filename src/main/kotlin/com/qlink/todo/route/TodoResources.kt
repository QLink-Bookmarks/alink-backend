package com.qlink.todo.route

import io.ktor.resources.Resource

@Resource("/todos")
class TodoResources(
    val status: String? = null,
    val orderKey: String? = null,
    val order: String? = null,
    val cursor: String? = null,
    val size: Int? = null,
    val isCompleted: Boolean? = null,
    val reminderAt: String? = null,
    val folderId: Long? = null,
) {
    @Resource("counts")
    class Counts(
        val parent: TodoResources = TodoResources(),
    )

    @Resource("{id}")
    class ById(
        val parent: TodoResources = TodoResources(),
        val id: Long,
    ) {
        @Resource("completed")
        class Completed(
            val parent: ById,
        )
    }
}
