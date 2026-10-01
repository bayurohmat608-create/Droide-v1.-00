package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentIdeRequestTest {
    private fun request(json: String) = AgentIdeRequest.parse(Json.parseToJsonElement(json).jsonObject)

    @Test fun everyAdvertisedOperationHasAValidRequest() {
        for (operation in AgentIdeRequest.OPERATIONS) {
            val args = buildJsonObject {
                put("operation", operation)
                if (operation in setOf("run_file", "debug_file", "debug_android", "debug_configurations", "debug_breakpoint_add", "debug_breakpoint_remove")) put("path", "src/main.kt")
                if (operation == "gradle_task") put("task", ":app:compileDebugKotlin")
                if (operation.startsWith("debug_breakpoint_")) put("line", 1)
                if (operation == "debug_evaluate") put("expression", "counter + 1")
            }
            assertEquals(operation, AgentIdeRequest.parse(args).operation)
        }
    }

    @Test fun pathsRemainExactAndPermissionDescribesTheEffectiveRequest() {
        val parsed = request("""{"operation":"debug_file","path":" src/odd ' name.kt ","configuration":" Local "}""")
        assertEquals(" src/odd ' name.kt ", parsed.path)
        val permission = Json.parseToJsonElement(parsed.permissionResource).jsonObject
        assertEquals(parsed.path, permission["path"]!!.jsonPrimitive.content)
        assertEquals("Local", permission["configuration"]!!.jsonPrimitive.content)
        assertEquals("debug_file", permission["operation"]!!.jsonPrimitive.content)
    }

    @Test fun pathsCannotEscapeTheWorkspace() {
        listOf("/tmp/file.kt", "../file.kt", "src/../file.kt", "src//file.kt", "./file.kt", "src\\file.kt", "file\u0000.kt").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { AgentIdeRequest.parse(buildJsonObject { put("operation", "run_file"); put("path", path) }) }
        }
    }

    @Test fun missingRequiredAndUnknownArgumentsAreRejected() {
        listOf("""{"operation":"run_file"}""", """{"operation":"gradle_task"}""", """{"operation":"debug_breakpoint_add","path":"main.kt"}""",
            """{"operation":"debug_evaluate"}""", """{"operation":"build_everything"}""", """{"operation":"status","shell":"rm"}""").forEach {
            assertThrows(IllegalArgumentException::class.java) { request(it) }
        }
    }

    @Test fun idsAndPagingUseStrictIntegerBounds() {
        listOf("\"1\"", "1.5", "true", "2147483648", "null", "-1").forEach { value ->
            assertThrows(RuntimeException::class.java) { request("""{"operation":"debug_variables","reference":$value}""") }
        }
        assertEquals(0, request("""{"operation":"debug_evaluate","expression":"x","frame_id":0}""").frameId)
        val paging = request("""{"operation":"debug_variables","reference":1,"start":0,"count":500}""")
        assertEquals(500, paging.count)
        assertThrows(IllegalArgumentException::class.java) { request("""{"operation":"debug_variables","reference":1,"count":501}""") }
    }

    @Test fun oneGradleTaskCannotInjectOptionsOrAnotherCommand() {
        assertEquals(":app:compileDebugKotlin", request("""{"operation":"gradle_task","task":" :app:compileDebugKotlin "}""").task)
        listOf("--init-script", "assembleDebug test", "assembleDebug;id", ":app:../test", "\$(id)").forEach { task ->
            assertThrows(IllegalArgumentException::class.java) { AgentIdeRequest.parse(buildJsonObject { put("operation", "gradle_task"); put("task", task) }) }
        }
    }

    @Test fun ignoredArgumentsAndAmbiguousVariableRequestsAreRejected() {
        listOf("""{"operation":"status","path":"main.kt"}""", """{"operation":"test","line":2}""", """{"operation":"test","thread_id":1}""",
            """{"operation":"test","frame_id":1}""", """{"operation":"test","count":1}""", """{"operation":"debug_evaluate","expression":"x","start":1}""",
            """{"operation":"debug_variables","frame_id":0,"reference":1}""").forEach {
            assertThrows(IllegalArgumentException::class.java) { request(it) }
        }
    }

    @Test fun everyDiskExecutingOperationReconcilesGeneratedFiles() {
        listOf("build_debug", "build_release_apk", "build_release_bundle", "gradle_task", "test", "lint", "run_file", "install_run", "debug_file", "debug_android").forEach {
            assertTrue(AgentIdeRequest(it).usesDisk)
            assertTrue(AgentIdeRequest(it).reconcileAfter)
        }
        assertFalse(AgentIdeRequest("debug_configurations").reconcileAfter)
        assertFalse(AgentIdeRequest("debug_status").usesDisk)
    }

    @Test fun debugInspectionAcceptsExplicitPagingForEveryList() {
        for (operation in listOf("debug_configurations", "debug_threads", "debug_stack", "debug_variables")) {
            val args = buildJsonObject {
                put("operation", operation); put("start", 20); put("count", 50)
                if (operation == "debug_configurations") put("path", "main.kt")
            }
            val parsed = AgentIdeRequest.parse(args)
            assertEquals(20, parsed.start)
            assertEquals(50, parsed.count)
        }
    }
}
