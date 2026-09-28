/*
 * Copyright (c) 2026, BayStudio contributors.
 *
 * Independently implemented Java compatibility layer of the BoringSSL SPAKE2/Edwards25519 behavior
 * used by Android Wireless Debugging. The protocol behavior is derived from the
 * permissively licensed AOSP/BoringSSL specification and a BSD-3-Clause
 * independent implementation. No GPL SPAKE2 source is included in this file.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.flyfish233.crypto.spake2;

import javax.security.auth.Destroyable;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * ABI-compatible SPAKE2 context for Kadb Android pairing.
 *
 * <p>This implementation follows BoringSSL's legacy SPAKE2-over-Edwards25519
 * transcript used by ADB pairing. It deliberately keeps the public API consumed
 * by Kadb so the upstream Apache-2.0 Kadb bytecode does not need to be forked.</p>
 *
 * <p>The BigInteger field implementation is correctness-oriented and has not
 * been independently certified as constant-time. Droide therefore keeps public
 * distribution fail-closed until physical-device interoperability and external
 * security review are complete.</p>
 */
public class Spake2Context implements Destroyable, AutoCloseable {
    public static final int MAX_MSG_SIZE = 32;
    public static final int MAX_KEY_SIZE = 64;
    public static final int MAX_NAME_SIZE = 4096;
    public static final int MAX_PASSWORD_SIZE = 65536;

    private static final BigInteger TWO = BigInteger.valueOf(2L);
    private static final BigInteger FIELD_P = TWO.pow(255).subtract(BigInteger.valueOf(19L));
    private static final BigInteger GROUP_L = TWO.pow(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger CURVE_D = mod(
            BigInteger.valueOf(-121665L)
                    .multiply(BigInteger.valueOf(121666L).modInverse(FIELD_P))
    );
    private static final BigInteger SQRT_M1 = TWO.modPow(FIELD_P.subtract(BigInteger.ONE).shiftRight(2), FIELD_P);

    private static final Point BASE = Point.decode(hex(
            "5866666666666666666666666666666666666666666666666666666666666666"));
    private static final Point POINT_M = Point.decode(hex(
            "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    private static final Point POINT_N = Point.decode(hex(
            "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));

    private enum State { INIT, MESSAGE_GENERATED, KEY_GENERATED, DESTROYED }

    private final byte[] myName;
    private final byte[] theirName;
    private final Spake2Role myRole;
    private final SecureRandom secureRandom;
    private final byte[] privateKey = new byte[32];
    private final byte[] myMsg = new byte[32];
    private final byte[] passwordScalar = new byte[32];
    private final byte[] passwordHash = new byte[64];

    private State state = State.INIT;
    private boolean disablePasswordScalarHack;

    public Spake2Context(Spake2Role myRole, byte[] myName, byte[] theirName) {
        this(myRole, myName, theirName, null);
    }

    public Spake2Context(Spake2Role myRole, byte[] myName, byte[] theirName, SecureRandom rng) {
        if (myRole == null) throw new IllegalArgumentException("Role must not be null");
        if (myName == null || theirName == null) throw new IllegalArgumentException("Participant names must not be null");
        if (myName.length > MAX_NAME_SIZE || theirName.length > MAX_NAME_SIZE) {
            throw new IllegalArgumentException("Participant name too large");
        }
        this.myRole = myRole;
        this.myName = myName.clone();
        this.theirName = theirName.clone();
        this.secureRandom = rng != null ? rng : new SecureRandom();
    }

    public void setDisablePasswordScalarHack(boolean disablePasswordScalarHack) {
        requireNotDestroyed();
        if (state != State.INIT) throw new IllegalStateException("Scalar policy cannot change after message generation");
        this.disablePasswordScalarHack = disablePasswordScalarHack;
    }

    public boolean isDisablePasswordScalarHack() {
        return disablePasswordScalarHack;
    }

    public Spake2Role getMyRole() {
        return myRole;
    }

    public byte[] getMyMsg() {
        requireNotDestroyed();
        return myMsg.clone();
    }

    public byte[] getMyName() {
        requireNotDestroyed();
        return myName.clone();
    }

    public byte[] getTheirName() {
        requireNotDestroyed();
        return theirName.clone();
    }

    @Override
    public boolean isDestroyed() {
        return state == State.DESTROYED;
    }

    @Override
    public void destroy() {
        if (state == State.DESTROYED) return;
        Arrays.fill(privateKey, (byte) 0);
        Arrays.fill(myMsg, (byte) 0);
        Arrays.fill(passwordScalar, (byte) 0);
        Arrays.fill(passwordHash, (byte) 0);
        Arrays.fill(myName, (byte) 0);
        Arrays.fill(theirName, (byte) 0);
        state = State.DESTROYED;
    }

    @Override
    public void close() {
        destroy();
    }

    public byte[] generateMessage(byte[] password) throws IllegalArgumentException, IllegalStateException {
        requireNotDestroyed();
        if (state != State.INIT) throw new IllegalStateException("Message already generated");
        if (password == null) throw new IllegalArgumentException("Password must not be null");
        if (password.length > MAX_PASSWORD_SIZE) throw new IllegalArgumentException("Password too large");

        // BoringSSL draws 64 bytes, reduces modulo the Ed25519 subgroup order,
        // then multiplies the resulting scalar by the cofactor (8).
        byte[] entropy = new byte[64];
        secureRandom.nextBytes(entropy);
        BigInteger ephemeral = fromLittleEndian(entropy).mod(GROUP_L).shiftLeft(3);
        Arrays.fill(entropy, (byte) 0);
        copy32(toLittleEndian(ephemeral, 32), privateKey);

        byte[] ph = sha512(password);
        System.arraycopy(ph, 0, passwordHash, 0, passwordHash.length);
        BigInteger w = fromLittleEndian(ph).mod(GROUP_L);
        if (!disablePasswordScalarHack) w = hardenPasswordScalar(w);
        copy32(toLittleEndian(w, 32), passwordScalar);
        Arrays.fill(ph, (byte) 0);

        Point t = BASE.scalarMultiply(ephemeral);
        Point blind = myRole == Spake2Role.Alice ? POINT_M : POINT_N;
        Point message = t.add(blind.scalarMultiply(w));
        byte[] encoded = message.encode();
        System.arraycopy(encoded, 0, myMsg, 0, myMsg.length);
        state = State.MESSAGE_GENERATED;
        return encoded;
    }

    public byte[] processMessage(byte[] theirMessage) throws IllegalArgumentException, IllegalStateException {
        requireNotDestroyed();
        if (state != State.MESSAGE_GENERATED) throw new IllegalStateException("Message must be generated first");
        if (theirMessage == null) throw new IllegalArgumentException("Peer message must not be null");
        if (theirMessage.length != MAX_MSG_SIZE) return null;

        final Point peer;
        try {
            peer = Point.decode(theirMessage);
        } catch (IllegalArgumentException invalidPoint) {
            state = State.KEY_GENERATED;
            return null;
        }

        BigInteger w = fromLittleEndian(passwordScalar);
        BigInteger ephemeral = fromLittleEndian(privateKey);
        Point peerBlind = myRole == Spake2Role.Alice ? POINT_N : POINT_M;
        Point unblinded = peer.subtract(peerBlind.scalarMultiply(w));
        byte[] shared = unblinded.scalarMultiply(ephemeral).encode();

        MessageDigest transcript = newSha512();
        if (myRole == Spake2Role.Alice) {
            updateLengthPrefixed(transcript, myName);
            updateLengthPrefixed(transcript, theirName);
            updateLengthPrefixed(transcript, myMsg);
            updateLengthPrefixed(transcript, theirMessage);
        } else {
            updateLengthPrefixed(transcript, theirName);
            updateLengthPrefixed(transcript, myName);
            updateLengthPrefixed(transcript, theirMessage);
            updateLengthPrefixed(transcript, myMsg);
        }
        updateLengthPrefixed(transcript, shared);
        updateLengthPrefixed(transcript, passwordHash);
        byte[] key = transcript.digest();
        Arrays.fill(shared, (byte) 0);
        state = State.KEY_GENERATED;
        return key;
    }

    private static BigInteger hardenPasswordScalar(BigInteger reduced) {
        BigInteger w = reduced;
        if (w.testBit(0)) w = w.add(GROUP_L);
        if (w.testBit(1)) w = w.add(GROUP_L.shiftLeft(1));
        if (w.testBit(2)) w = w.add(GROUP_L.shiftLeft(2));
        if (w.and(BigInteger.valueOf(7L)).signum() != 0) {
            throw new IllegalStateException("SPAKE2 password scalar hardening failed");
        }
        return w;
    }

    private static void updateLengthPrefixed(MessageDigest digest, byte[] data) {
        long n = data.length & 0xffffffffL;
        for (int i = 0; i < 8; i++) {
            digest.update((byte) (n & 0xff));
            n >>>= 8;
        }
        digest.update(data);
    }

    private static MessageDigest newSha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-512 unavailable", impossible);
        }
    }

    private static byte[] sha512(byte[] input) {
        return newSha512().digest(input);
    }

    private void requireNotDestroyed() {
        if (state == State.DESTROYED) throw new IllegalStateException("SPAKE2 context is destroyed");
    }

    private static void copy32(byte[] src, byte[] dst) {
        if (src.length != 32 || dst.length != 32) throw new IllegalArgumentException("Expected 32-byte scalar");
        System.arraycopy(src, 0, dst, 0, 32);
    }

    private static BigInteger mod(BigInteger v) {
        BigInteger r = v.remainder(FIELD_P);
        return r.signum() < 0 ? r.add(FIELD_P) : r;
    }

    private static BigInteger fromLittleEndian(byte[] bytes) {
        byte[] reversed = bytes.clone();
        reverse(reversed);
        return new BigInteger(1, reversed);
    }

    private static byte[] toLittleEndian(BigInteger value, int length) {
        if (value.signum() < 0) throw new IllegalArgumentException("Negative integer");
        byte[] be = value.toByteArray();
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            int src = be.length - 1 - i;
            out[i] = src >= 0 ? be[src] : 0;
        }
        return out;
    }

    private static void reverse(byte[] bytes) {
        for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
            byte t = bytes[i]; bytes[i] = bytes[j]; bytes[j] = t;
        }
    }

    private static byte[] hex(String text) {
        if ((text.length() & 1) != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(text.charAt(i * 2), 16);
            int lo = Character.digit(text.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Extended Edwards point, avoiding field inversion inside scalar loops. */
    private static final class Point {
        final BigInteger x;
        final BigInteger y;
        final BigInteger z;
        final BigInteger t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = mod(x); this.y = mod(y); this.z = mod(z); this.t = mod(t);
        }

        static Point identity() {
            return new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
        }

        static Point affine(BigInteger x, BigInteger y) {
            return new Point(x, y, BigInteger.ONE, x.multiply(y));
        }

        Point add(Point q) {
            BigInteger a = mod(y.subtract(x).multiply(q.y.subtract(q.x)));
            BigInteger b = mod(y.add(x).multiply(q.y.add(q.x)));
            BigInteger c = mod(t.multiply(q.t).multiply(CURVE_D).shiftLeft(1));
            BigInteger d2 = mod(z.multiply(q.z).shiftLeft(1));
            BigInteger e = mod(b.subtract(a));
            BigInteger f = mod(d2.subtract(c));
            BigInteger g = mod(d2.add(c));
            BigInteger h = mod(b.add(a));
            return new Point(e.multiply(f), g.multiply(h), f.multiply(g), e.multiply(h));
        }

        Point dbl() {
            BigInteger a = mod(x.multiply(x));
            BigInteger b = mod(y.multiply(y));
            BigInteger c = mod(z.multiply(z).shiftLeft(1));
            BigInteger d2 = mod(a.negate());
            BigInteger e = mod(x.add(y).pow(2).subtract(a).subtract(b));
            BigInteger g = mod(d2.add(b));
            BigInteger f = mod(g.subtract(c));
            BigInteger h = mod(d2.subtract(b));
            return new Point(e.multiply(f), g.multiply(h), f.multiply(g), e.multiply(h));
        }

        Point negate() {
            return new Point(x.negate(), y, z, t.negate());
        }

        Point subtract(Point q) {
            return add(q.negate());
        }

        Point scalarMultiply(BigInteger scalar) {
            if (scalar.signum() < 0 || scalar.bitLength() > 256) {
                throw new IllegalArgumentException("Scalar outside 256-bit range");
            }
            Point result = identity();
            Point addend = this;
            for (int bit = 0; bit < 256; bit++) {
                if (scalar.testBit(bit)) result = result.add(addend);
                addend = addend.dbl();
            }
            return result;
        }

        byte[] encode() {
            BigInteger invZ = z.modInverse(FIELD_P);
            BigInteger ax = mod(x.multiply(invZ));
            BigInteger ay = mod(y.multiply(invZ));
            byte[] out = toLittleEndian(ay, 32);
            out[31] = (byte) (out[31] & 0x7f);
            if (ax.testBit(0)) out[31] = (byte) (out[31] | 0x80);
            return out;
        }

        static Point decode(byte[] encoded) {
            if (encoded == null || encoded.length != 32) throw new IllegalArgumentException("Point must be 32 bytes");
            byte[] yBytes = encoded.clone();
            int sign = (yBytes[31] >>> 7) & 1;
            yBytes[31] &= 0x7f;
            BigInteger y = fromLittleEndian(yBytes);
            if (y.compareTo(FIELD_P) >= 0) throw new IllegalArgumentException("Non-canonical Edwards point");

            BigInteger y2 = mod(y.multiply(y));
            BigInteger numerator = mod(y2.subtract(BigInteger.ONE));
            BigInteger denominator = mod(CURVE_D.multiply(y2).add(BigInteger.ONE));
            if (denominator.signum() == 0) throw new IllegalArgumentException("Invalid Edwards point");
            BigInteger x2 = mod(numerator.multiply(denominator.modInverse(FIELD_P)));
            BigInteger x = sqrt(x2);
            if (x == null) throw new IllegalArgumentException("Point is not on Edwards25519");
            if (x.signum() == 0 && sign != 0) throw new IllegalArgumentException("Invalid Edwards sign bit");
            if ((x.testBit(0) ? 1 : 0) != sign) x = FIELD_P.subtract(x);

            BigInteger lhs = mod(y2.subtract(x.multiply(x)));
            BigInteger rhs = mod(BigInteger.ONE.add(CURVE_D.multiply(x).multiply(x).multiply(y2)));
            if (!lhs.equals(rhs)) throw new IllegalArgumentException("Point equation mismatch");
            return affine(x, y);
        }

        private static BigInteger sqrt(BigInteger value) {
            BigInteger r = value.modPow(FIELD_P.add(BigInteger.valueOf(3L)).shiftRight(3), FIELD_P);
            if (!mod(r.multiply(r)).equals(value)) r = mod(r.multiply(SQRT_M1));
            return mod(r.multiply(r)).equals(value) ? r : null;
        }
    }
}
