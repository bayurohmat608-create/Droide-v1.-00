package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.LspWorkspaceEdit
import com.baystudio.droide.core.TextEditApplier

 
internal object LspWorkspaceEditPreflight {
    suspend fun prepare(edit: LspWorkspaceEdit, state: EditorWorkspaceState, files: FileRepository): Map<String, String> {
        require(edit.edits.isNotEmpty()) { "Language server returned no edits" }
        val updates = linkedMapOf<String, String>()
        val revisions = linkedMapOf<String, Int>()
        for ((path, edits) in edit.edits.groupBy { it.path }) {
            val doc = state.document(path)
            doc.ensureLoaded(files)
            check(doc.agentEditable) { "Workspace edit targets a large/performance-mode or non-text document: $path" }
            val expected = edit.expectedContent[path]
            require(expected != null && doc.content == expected) {
                "Document changed since the language-server request: $path. Re-run the action."
            }
            revisions[path] = doc.changeVersion
            updates[path] = TextEditApplier.apply(doc.content, edits.map { it.range })
        }
        
        for ((path, revision) in revisions) {
            val doc = state.document(path)
            check(doc.changeVersion == revision && doc.content == edit.expectedContent[path]) {
                "Document changed while preparing language-server edits: $path. Re-run the action."
            }
        }
        return updates
    }
}
