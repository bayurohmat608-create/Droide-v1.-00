package com.baystudio.droide.core
import java.io.File
import java.security.KeyStore
import java.util.Base64

 
object LocalGuestCertificates {
    fun seed(rootfs: File) {
        val target = File(rootfs, "etc/ssl/certs/ca-certificates.crt")
        LocalExecutionSubstrate.requireSafeLocalPath(target.absolutePath)
        if (target.isFile && target.length() > 0) return
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val pem = buildString {
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (!alias.startsWith("system:")) continue
                val cert = store.getCertificate(alias) ?: continue
                append("-----BEGIN CERTIFICATE-----\n")
                append(Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(cert.encoded))
                append("\n-----END CERTIFICATE-----\n")
            }
        }
        check(pem.isNotEmpty()) { "Android system CA store is unavailable" }
        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
        target.writeText(pem)
    }
}
