package com.baystudio.droide.core

 
internal object TerminalTitlePolicy {
    fun display(raw: String?): String? = raw
        ?.asSequence()
        ?.filterNot { char ->
            Character.isISOControl(char) ||
                char in '\u202a'..'\u202e' ||
                char in '\u2066'..'\u2069' ||
                char == '\u200e' || char == '\u200f' || char == '\u061c'
        }
        ?.take(80)
        ?.joinToString("")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
