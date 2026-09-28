package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionEngineTest {
    @Test fun ordinaryReadsAreAllowed() {
        assertEquals(PermEffect.ALLOW, PermissionEngine().decide("read", "src/Main.kt"))
    }

    @Test fun commonSecretsRequireApproval() {
        val p = PermissionEngine()
        listOf(
            ".env",
            "app/.env.local",
            "id_rsa",
            "cert.pem",
            ".npmrc",
            ".droide/sessions/a.json",
            ".git/config",
            "packages/app/.droide/session.json",
            "subproject/.git/config",
        ).forEach {
            assertEquals("sensitive path should ASK: $it", PermEffect.ASK, p.decide("read", it))
        }
    }

    @Test fun arbitraryCodeExecutionRequiresApproval() {
        val p = PermissionEngine()
        assertEquals(PermEffect.ASK, p.decide("shell", "python3 script.py"))
        assertEquals(PermEffect.ASK, p.decide("shell", "cat .env"))
        assertEquals(PermEffect.ASK, p.decide("shell", "git status"))
        assertEquals(PermEffect.ALLOW, p.decide("shell", "ls"))
        assertEquals(PermEffect.ASK, p.decide("shell", "ls src"))
        assertEquals(PermEffect.ASK, p.decide("shell", "ls ; rm -rf files"))
        assertEquals(PermEffect.ASK, p.decide("shell", "git status; echo injected"))
    }
    @Test fun interactiveSessionGrantIsExactAndAgentScoped() {
        val p = PermissionEngine()
        p.allowExactForSession("shell", "echo *", "build")
        assertEquals(PermEffect.ALLOW, p.decide("shell", "echo *", "build"))
        assertEquals(PermEffect.ASK, p.decide("shell", "echo secret", "build"))
        assertEquals(PermEffect.ASK, p.decide("shell", "echo *", "reviewer"))
    }

    @Test fun exactSessionGrantCannotOverrideUpdatedDeny() {
        val p = PermissionEngine()
        p.allowExactForSession("shell", "npm test", "build")
        assertEquals(PermEffect.ALLOW, p.decide("shell", "npm test", "build"))
        p.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("shell", "npm test", PermEffect.DENY))))
        assertEquals(PermEffect.DENY, p.decide("shell", "npm test", "build"))
    }

}
