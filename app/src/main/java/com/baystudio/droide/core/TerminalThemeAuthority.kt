package com.baystudio.droide.core

import com.termux.terminal.TerminalColors
import java.util.Properties
import java.util.WeakHashMap

 
object TerminalThemeAuthority {
    private val lock = Any()
    private var defaultFingerprint: Int = 0
    private val appliedSessions = WeakHashMap<ITerminalSession, Int>()

    fun apply(session: ITerminalSession, palette: DroideTerminalPalette) {
        val next = palette.hashCode()
        installDefaults(palette)
        synchronized(lock) {
            if (appliedSessions[session] == next) return
            when (session) {
                is NativePtySessionHandle -> session.nativePtySession.emulator?.mColors?.reset()
                is DevicePtySessionHandle -> session.terminalEmulator.resetThemeColors()
            }
            appliedSessions[session] = next
        }
    }

    fun installDefaults(palette: DroideTerminalPalette) {
        val next = palette.hashCode()
        synchronized(lock) {
            if (next == defaultFingerprint) return
            val props = Properties().apply {
                setProperty("background", hex(palette.background))
                setProperty("foreground", hex(palette.foreground))
                setProperty("cursor", hex(palette.cursor))
                palette.ansi.take(16).forEachIndexed { index, color -> setProperty("color$index", hex(color)) }
            }
            TerminalColors.COLOR_SCHEME.updateWith(props)
            defaultFingerprint = next
        }
    }

    private fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}
