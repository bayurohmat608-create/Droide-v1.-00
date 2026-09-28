package com.baystudio.droide.core

import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RemoteRefUpdate

 
object GitPushOutcome {
    fun render(results: Iterable<PushResult>): String {
        val updates = results.flatMap { result ->
            result.remoteUpdates.map { update ->
                val detail = update.message?.takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" }.orEmpty()
                "${update.remoteName}: ${update.status}$detail" to update.status
            }
        }
        return renderStatuses(updates)
    }

    internal fun renderStatuses(updates: List<Pair<String, RemoteRefUpdate.Status>>): String {
        if (updates.isEmpty()) return "git error: push returned no remote ref statuses"
        val failed = updates.any { (_, status) ->
            status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE
        }
        val headline = when {
            failed -> "git error: push rejected or incomplete"
            updates.all { it.second == RemoteRefUpdate.Status.UP_TO_DATE } -> "Push up to date"
            else -> "Push successful"
        }
        return (listOf(headline) + updates.map { it.first }).joinToString("\n")
    }
}
