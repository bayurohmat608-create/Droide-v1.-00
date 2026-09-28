package com.baystudio.droide.core

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsTest {
    @Test fun builtInDefinitionsHaveUniqueFunctionNamesAndRequiredSchemas() {
        val names = AgentTools.definitions.map { element ->
            val fn = element.jsonObject.getValue("function").jsonObject
            assertEquals("object", fn.getValue("parameters").jsonObject.getValue("type").jsonPrimitive.content)
            fn.getValue("name").jsonPrimitive.content
        }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.containsAll(listOf("read_file", "write_file", "run_command", "environment_probe", "git_status", "lsp")))
    }

    @Test fun aliasesMapToSamePermissionClassAsCanonicalTools() {
        assertEquals(AgentTools.actionOf("run_command"), AgentTools.actionOf("bash"))
        assertEquals(AgentTools.actionOf("edit_file"), AgentTools.actionOf("edit"))
        assertEquals(AgentTools.actionOf("write_file"), AgentTools.actionOf("write"))
        assertEquals(AgentTools.actionOf("read_file"), AgentTools.actionOf("read"))
        assertEquals(AgentTools.actionOf("search_files"), AgentTools.actionOf("grep"))
    }

    @Test fun mutatingAndExecutionToolsNeverMapToReadPermission() {
        listOf("write_file", "edit_file", "apply_patch", "run_command", "git_commit", "git_push", "worktree")
            .forEach { name -> assertTrue("$name must be gated", AgentTools.actionOf(name) != "read") }
    }
}
