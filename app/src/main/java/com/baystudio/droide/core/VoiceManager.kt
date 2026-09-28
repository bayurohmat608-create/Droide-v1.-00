package com.baystudio.droide.core

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*





object VoiceManager {
    fun cleanup(transcript: String): String {
        return transcript
            .replace(Regex("\\b(um|uh|eh|anu)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(.+)\\b.*\\b\\1\\b"), "$1") 
            .replace(Regex("\\s+"), " ").trim()
    }
}
