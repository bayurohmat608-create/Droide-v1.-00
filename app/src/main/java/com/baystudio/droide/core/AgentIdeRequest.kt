package com.baystudio.droide.core

import kotlinx.serialization.json.*


internal data class AgentIdeRequest(
    val operation: String,
    val path: String? = null,
    val task: String? = null,
    val configuration: String? = null,
    val line: Int? = null,
    val threadId: Int? = null,
    val frameId: Int? = null,
    val reference: Int? = null,
    val start: Int? = null,
    val count: Int? = null,
    val expression: String? = null,
) {
    val usesDisk: Boolean get() = operation in DISK_OPERATIONS
    val reconcileAfter: Boolean get() = usesDisk && operation != "debug_configurations"
    val permissionResource: String get() = buildJsonObject {
        put("operation", operation)
        path?.let { put("path", it) }
        task?.let { put("task", it) }
        configuration?.let { put("configuration", it) }
        line?.let { put("line", it) }
        threadId?.let { put("thread_id", it) }
        frameId?.let { put("frame_id", it) }
        reference?.let { put("reference", it) }
        start?.let { put("start", it) }
        count?.let { put("count", it) }
        expression?.let { put("expression", it) }
    }.toString()

    companion object {
        val OPERATIONS = listOf(
            "status", "build_debug", "build_release_apk", "build_release_bundle", "gradle_task", "test", "lint",
            "run_file", "install_run", "cancel", "debug_status", "debug_configurations", "debug_file", "debug_android",
            "debug_continue", "debug_pause", "debug_next", "debug_step_in", "debug_step_out", "debug_stop",
            "debug_breakpoint_add", "debug_breakpoint_remove", "debug_threads", "debug_stack", "debug_variables", "debug_evaluate",
        )
        private val DISK_OPERATIONS = setOf(
            "build_debug", "build_release_apk", "build_release_bundle", "gradle_task", "test", "lint",
            "run_file", "install_run", "debug_configurations", "debug_file", "debug_android",
        )
        private val PATH_OPERATIONS = setOf("run_file", "debug_file", "debug_android", "debug_configurations", "debug_breakpoint_add", "debug_breakpoint_remove")
        private val THREAD_OPERATIONS = setOf("debug_continue", "debug_pause", "debug_next", "debug_step_in", "debug_step_out", "debug_stack")
        private val PAGED_OPERATIONS = setOf("debug_configurations", "debug_threads", "debug_stack", "debug_variables")
        private val FIELDS = setOf("operation", "path", "task", "configuration", "line", "thread_id", "frame_id", "reference", "start", "count", "expression")

        fun parse(args: JsonObject): AgentIdeRequest {
            require(args.keys.all { it in FIELDS }) { "Unknown ide argument" }
            fun string(name: String, limit: Int = 4096, trim: Boolean = true): String? {
                val value = args[name] ?: return null
                require(value is JsonPrimitive && value.isString) { "ide $name must be a string" }
                return (if (trim) value.content.trim() else value.content).also {
                    require(it.isNotBlank() && it.length <= limit && it.none { c -> c.code < 0x20 || c.code == 0x7f }) {
                        "Invalid ide $name"
                    }
                }
            }
            fun integer(name: String, minimum: Int = 1, maximum: Int = Int.MAX_VALUE): Int? {
                val value = args[name] ?: return null
                require(value is JsonPrimitive && !value.isString) { "ide $name must be an integer" }
                return (value.intOrNull ?: error("ide $name must be an integer")).also {
                    require(it in minimum..maximum) { "ide $name must be between $minimum and $maximum" }
                }
            }
            val operation = string("operation", 64) ?: "status"
            require(operation in OPERATIONS) { "Unsupported ide operation: $operation" }
            val path = string("path", trim = false)?.also {
                require(!it.startsWith('/') && '\\' !in it && it.split('/').none { part -> part in setOf("", ".", "..") }) {
                    "ide path must be a relative workspace file"
                }
            }
            val task = string("task", 120)?.let { GradleTaskPath.parse(it).raw }
            val request = AgentIdeRequest(
                operation, path, task, string("configuration", 240), integer("line"), integer("thread_id"),
                integer("frame_id", 0), integer("reference"), integer("start", 0), integer("count", maximum = 500), string("expression", 20_000),
            )
            if (operation in PATH_OPERATIONS) {
                require(path != null) { "ide $operation requires path" }
            }
            if (operation == "gradle_task") require(task != null) { "ide gradle_task requires task" }
            if (operation in setOf("debug_breakpoint_add", "debug_breakpoint_remove")) require(request.line != null) { "ide $operation requires line" }
            if (operation == "debug_evaluate") require(request.expression != null) { "ide debug_evaluate requires expression" }
            require(request.configuration == null || operation in setOf("debug_file", "debug_android")) { "configuration applies only to debug start" }
            require(task == null || operation in setOf("gradle_task", "install_run", "debug_android")) { "task does not apply to $operation" }
            require(request.expression == null || operation == "debug_evaluate") { "expression applies only to debug_evaluate" }
            require(path == null || operation in PATH_OPERATIONS) { "path does not apply to $operation" }
            require(request.line == null || operation in setOf("debug_breakpoint_add", "debug_breakpoint_remove")) { "line applies only to breakpoints" }
            require(request.threadId == null || operation in THREAD_OPERATIONS) { "thread_id does not apply to $operation" }
            require(request.frameId == null || operation in setOf("debug_variables", "debug_evaluate")) { "frame_id does not apply to $operation" }
            require(request.reference == null || operation == "debug_variables") { "reference applies only to debug_variables" }
            require(request.start == null || operation in PAGED_OPERATIONS) { "start applies only to paged debug inspection" }
            require(request.count == null || operation in PAGED_OPERATIONS) { "count applies only to paged debug inspection" }
            require(request.reference == null || request.frameId == null) { "Choose either reference or frame_id for variables" }
            return request
        }
    }
}
