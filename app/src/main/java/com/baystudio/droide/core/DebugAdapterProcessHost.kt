package com.baystudio.droide.core

import kotlinx.coroutines.CoroutineScope

 
fun StdioProcessHost.forDebugAdapterTransport(
    scope: CoroutineScope,
    transport: DebugAdapterTransport,
    reverseTcpContract: AuthenticatedReverseTcpContract?,
): StdioProcessHost = when (transport) {
    DebugAdapterTransport.STDIO -> this
    DebugAdapterTransport.LOOPBACK_TCP_SERVER -> LoopbackTcpServerProcessHost(this, scope)
    DebugAdapterTransport.AUTHENTICATED_REVERSE_TCP -> AuthenticatedReverseTcpProcessHost(
        delegate = this,
        scope = scope,
        contract = requireNotNull(reverseTcpContract) { "Reverse-TCP debugger contract is missing" },
    )
}
