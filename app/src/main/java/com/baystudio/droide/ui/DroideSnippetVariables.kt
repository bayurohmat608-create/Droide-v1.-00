package com.baystudio.droide.ui

import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.snippet.variable.FileBasedSnippetVariableResolver
import io.github.rosemoe.sora.widget.snippet.variable.WorkspaceBasedSnippetVariableResolver
import java.io.File

 
internal fun configureDroideSnippetVariables(editor: CodeEditor, workspaceRoot: File, relativePath: String) {
    val file = runCatching { File(workspaceRoot, relativePath).canonicalFile }.getOrElse { File(workspaceRoot, relativePath).absoluteFile }
    val root = runCatching { workspaceRoot.canonicalFile }.getOrElse { workspaceRoot.absoluteFile }
    editor.snippetController.fileVariableResolver = object : FileBasedSnippetVariableResolver() {
        override fun resolve(name: String): String = when (name) {
            "TM_FILENAME" -> file.name
            "TM_FILENAME_BASE" -> file.nameWithoutExtension
            "TM_DIRECTORY" -> file.parentFile?.absolutePath.orEmpty()
            "TM_FILEPATH" -> file.absolutePath
            "RELATIVE_PATH" -> runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrDefault(relativePath)
            else -> ""
        }
    }
    editor.snippetController.workspaceVariableResolver = object : WorkspaceBasedSnippetVariableResolver() {
        override fun resolve(name: String): String = when (name) {
            "WORKSPACE_NAME" -> root.name
            "WORKSPACE_FOLDER" -> root.absolutePath
            else -> ""
        }
    }
}
