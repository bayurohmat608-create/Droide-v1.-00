package com.baystudio.droide.core

 
object EditorReviewModePolicy {
    fun canEditText(isText: Boolean, loaded: Boolean, reviewMode: Boolean): Boolean =
        isText && loaded && !reviewMode

    fun canAgentEdit(canEdit: Boolean, fullIntelligence: Boolean): Boolean =
        canEdit && fullIntelligence
}
