package com.baystudio.droide.core

 
object ThemeContrastPolicy {
    fun opaque(color: Int): Int = color or 0xFF000000.toInt()

    fun mix(a: Int, b: Int, amount: Double): Int {
        val t = amount.coerceIn(0.0, 1.0)
        fun channel(value: Int, shift: Int): Int = (value ushr shift) and 0xFF
        fun c(shift: Int): Int = (channel(a, shift) * (1.0 - t) + channel(b, shift) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    fun luminance(color: Int): Double {
        fun channel(value: Int, shift: Int): Int = (value ushr shift) and 0xFF
        fun linear(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(channel(color, 16)) + 0.7152 * linear(channel(color, 8)) + 0.0722 * linear(channel(color, 0))
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(opaque(a))
        val lb = luminance(opaque(b))
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun ensureTextContrast(background: Int, requested: Int, minimum: Double = 4.5): Int {
        val bg = opaque(background)
        val candidate = opaque(requested)
        if (contrast(bg, candidate) >= minimum) return candidate
        return bestMonochromeForeground(bg)
    }

    fun ensureAccentVisibility(background: Int, requested: Int): Int {
        val bg = opaque(background)
        val candidate = opaque(requested)
        if (contrast(bg, candidate) >= 3.0) return candidate
        return if (luminance(bg) < 0.42) 0xFF58A6FF.toInt() else 0xFF0969DA.toInt()
    }

    fun bestMonochromeForeground(background: Int): Int {
        val bg = opaque(background)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        return if (contrast(bg, black) >= contrast(bg, white)) black else white
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}
