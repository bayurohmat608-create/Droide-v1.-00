package com.baystudio.droide.core

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

 
data class PendingQuestion(
    val id: Long,
    val header: String,
    val question: String,
    val options: List<String>,
    val provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
)

class QuestionManager {
    private var nextId = 1L
    private val _pending = MutableStateFlow<PendingQuestion?>(null)
    val pending: StateFlow<PendingQuestion?> = _pending.asStateFlow()
    private val conts = mutableMapOf<Long, kotlin.coroutines.Continuation<String>>()
    private val askMutex = Mutex()

     
    suspend fun ask(
        header: String,
        question: String,
        options: List<String>,
        provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
    ): String = askMutex.withLock {
        val q = PendingQuestion(
            id = nextId++,
            header = provenance.decorateHeader(header),
            question = question.take(500),
            options = options.take(6),
            provenance = provenance,
        )
        _pending.value = q
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            conts[q.id] = cont
            cont.invokeOnCancellation {
                conts.remove(q.id)
                if (_pending.value?.id == q.id) _pending.value = null
            }
        }
    }

    fun answer(text: String) {
        val q = _pending.value ?: return
        _pending.value = null
        conts.remove(q.id)?.resumeWith(Result.success(text))
    }
}
