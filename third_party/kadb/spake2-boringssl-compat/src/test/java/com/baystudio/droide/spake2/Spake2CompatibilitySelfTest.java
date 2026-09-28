/*
 * Copyright (c) 2026, BayStudio contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.baystudio.droide.spake2;

import com.flyfish233.crypto.spake2.Spake2Context;
import com.flyfish233.crypto.spake2.Spake2Role;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/** Deterministic cross-language compatibility vector for the ADB/BoringSSL SPAKE2 transcript. */
public final class Spake2CompatibilitySelfTest {
    private static final String ALICE_MESSAGE =
            "950ec4a2329addab36f0254719a24be86919b1cf68df68d910a3ea590ff599d4";
    private static final String BOB_MESSAGE =
            "88ee0ffb94efcfd1c62ad0b1c0a8120cfe50269ee3f6731eb7eaa07e84dd7cd4";
    private static final String SHARED_KEY =
            "0655cf969234865db8e36fedad2d2b9a2406ec8774e0a325f3a9fdc3f607082f" +
            "e8100a016738d5a718698102dbf83e3374cda518ad0be3f5aa989b98dbbcb259";

    private Spake2CompatibilitySelfTest() {}

    private static final class FixedSecureRandom extends SecureRandom {
        private final byte[] bytes;

        FixedSecureRandom(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public void nextBytes(byte[] destination) {
            if (destination.length != bytes.length) {
                throw new AssertionError("unexpected entropy request: " + destination.length);
            }
            System.arraycopy(bytes, 0, destination, 0, destination.length);
        }
    }

    public static void main(String[] args) {
        byte[] aliceName = "adb pair client\0".getBytes(StandardCharsets.UTF_8);
        byte[] bobName = "adb pair server\0".getBytes(StandardCharsets.UTF_8);
        byte[] password = "123456".getBytes(StandardCharsets.UTF_8);

        Spake2Context alice = new Spake2Context(
                Spake2Role.Alice, aliceName, bobName, new FixedSecureRandom(range(1, 64)));
        Spake2Context bob = new Spake2Context(
                Spake2Role.Bob, bobName, aliceName, new FixedSecureRandom(range(65, 64)));
        try {
            byte[] aliceMessage = alice.generateMessage(password);
            byte[] bobMessage = bob.generateMessage(password);
            expectHex("alice-message", aliceMessage, ALICE_MESSAGE);
            expectHex("bob-message", bobMessage, BOB_MESSAGE);

            byte[] aliceKey = alice.processMessage(bobMessage);
            byte[] bobKey = bob.processMessage(aliceMessage);
            expectHex("alice-shared-key", aliceKey, SHARED_KEY);
            expectHex("bob-shared-key", bobKey, SHARED_KEY);
            if (!Arrays.equals(aliceKey, bobKey)) {
                throw new AssertionError("Alice/Bob shared keys differ");
            }
        } finally {
            alice.close();
            bob.close();
        }
        if (!alice.isDestroyed() || !bob.isDestroyed()) {
            throw new AssertionError("destroy/close contract failed");
        }
        System.out.println("SPAKE2_COMPATIBILITY_VECTOR_OK");
    }

    private static byte[] range(int start, int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) out[i] = (byte) (start + i);
        return out;
    }

    private static void expectHex(String label, byte[] actual, String expected) {
        String got = hex(actual);
        if (!got.equals(expected)) {
            throw new AssertionError(label + " mismatch: " + got + " != " + expected);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }
}
