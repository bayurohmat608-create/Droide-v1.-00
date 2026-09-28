package com.baystudio.droide.core

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.FileDescriptor












internal object NativePtyCompatibility {
    @Volatile
    private var prepared: Boolean? = null

    fun prepare(): Boolean {
        prepared?.let { return it }
        return synchronized(this) {
            prepared?.let { return@synchronized it }
            val ready = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    if (!HiddenApiBypass.addHiddenApiExemptions("Ljava/io/FileDescriptor;")) {
                        return@synchronized false.also { prepared = it }
                    }
                }
                


                canWrapFileDescriptorReflectively()
            } catch (_: Exception) {
                false
            } catch (_: LinkageError) {
                false
            }
            prepared = ready
            ready
        }
    }

    private fun canWrapFileDescriptorReflectively(): Boolean {
        val descriptorField = try {
            FileDescriptor::class.java.getDeclaredField("descriptor")
        } catch (_: NoSuchFieldException) {
            FileDescriptor::class.java.getDeclaredField("fd")
        }
        descriptorField.isAccessible = true
        val probe = FileDescriptor()
        descriptorField.set(probe, -1)
        return descriptorField.get(probe) == -1
    }
}
