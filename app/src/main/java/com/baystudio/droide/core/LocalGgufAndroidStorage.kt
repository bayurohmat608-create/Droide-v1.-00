package com.baystudio.droide.core

import android.content.Context
import java.io.File

 
internal object LocalGgufAndroidStorage {
    fun root(context: Context): File =
        (context.getExternalFilesDir("local-gguf") ?: File(context.noBackupFilesDir, "local-gguf"))
            .apply { mkdirs() }
}
