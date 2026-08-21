package me.bmax.apatch.util

import android.util.Base64
import android.util.Log
import me.bmax.apatch.APApplication
import me.bmax.apatch.Natives
import me.bmax.apatch.apApp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object AppCloakUtils {
    private const val TAG = "AppCloakUtils"
    const val ORIGINAL_PKG = "me.bmax.apatch"

    fun isAppCloaked(): Boolean {
        return apApp.packageName != ORIGINAL_PKG
    }

    fun generateRandomPackageName(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rand = (1..3).map { chars.random() }.joinToString("")
        return "com.sys.app$rand" // total 14 chars matching "me.bmax.apatch"
    }

    fun cloakApp(newPkgName: String): Boolean {
        return repackageAndInstall(apApp.packageName, newPkgName)
    }

    fun restoreApp(): Boolean {
        return repackageAndInstall(apApp.packageName, ORIGINAL_PKG)
    }

    private fun repackageAndInstall(currentPkg: String, targetPkg: String): Boolean {
        try {
            Natives.su(0, APApplication.MAGISK_SCONTEXT)

            val srcApk = File(apApp.applicationInfo.sourceDir)
            if (!srcApk.exists()) {
                Log.e(TAG, "Source APK not found at ${srcApk.absolutePath}")
                return false
            }

            val tmpDir = File("/data/local/tmp")
            tmpDir.mkdirs()
            rootShellForResult("chmod 777 /data/local/tmp")

            val outApk = File(tmpDir, "apatch_cloaked.apk")
            if (outApk.exists()) outApk.delete()

            // 1. Rebuild and Sign APK with new package name
            val repackSuccess = rebuildAndSignApk(srcApk, outApk, currentPkg, targetPkg)
            if (!repackSuccess || !outApk.exists()) {
                Log.e(TAG, "Rebuild and sign APK failed")
                return false
            }

            // Ensure full world-readable permissions for Android package manager service
            rootShellForResult("chmod 777 '${outApk.absolutePath}'")

            // 2. Install the repackaged APK with multi-strategy root install
            val apkSize = outApk.length()
            val installCmds = arrayOf(
                "cat '${outApk.absolutePath}' | pm install -S $apkSize -r -d -g 2>&1",
                "pm install -r -d -g --bypass-low-target-sdk-block '${outApk.absolutePath}' 2>&1",
                "pm install -r -d -g '${outApk.absolutePath}' 2>&1",
                "cmd package install -r -d -g '${outApk.absolutePath}' 2>&1"
            )

            var installed = false
            for (cmd in installCmds) {
                val res = rootShellForResult(cmd)
                val output = (res.out + res.err).joinToString(" ")
                Log.i(TAG, "Exec '$cmd' -> $output")
                if (output.contains("Success", ignoreCase = true)) {
                    installed = true
                    break
                }
            }

            if (!installed) {
                Log.e(TAG, "All pm install strategies failed")
                outApk.delete()
                return false
            }

            // 3. Obtain new UID and grant in package_config & transfer preferences
            val uidRes = rootShellForResult("pm list packages -U $targetPkg")
            val uidLine = uidRes.out.find { it.contains(targetPkg) }
            val newUid = Regex("uid:(\\d+)").find(uidLine ?: "")?.groupValues?.get(1)

            if (!newUid.isNullOrEmpty()) {
                rootShellForResult(
                    "echo '$targetPkg,0,1,$newUid,0,${APApplication.MAGISK_SCONTEXT}' >> ${APApplication.PACKAGE_CONFIG_FILE}",
                    "mkdir -p /data/data/$targetPkg/shared_prefs",
                    "cp -rf /data/data/$currentPkg/shared_prefs/* /data/data/$targetPkg/shared_prefs/ 2>/dev/null || true",
                    "chown -R $newUid:$newUid /data/data/$targetPkg 2>/dev/null || true",
                    "killall -HUP apd 2>/dev/null || true"
                )
            }

            // 4. Launch new package and uninstall old package
            rootShellForResult(
                "am start -n '$targetPkg/me.bmax.apatch.ui.MainActivity'",
                "sleep 1",
                if (currentPkg != targetPkg) "pm uninstall '$currentPkg' &" else "true"
            )

            outApk.delete()
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "repackageAndInstall error: ", e)
            return false
        }
    }

    private fun rebuildAndSignApk(srcApk: File, destApk: File, oldPkg: String, newPkg: String): Boolean {
        try {
            val (privateKey, cert) = loadSigningKeyAndCert()

            val zipFile = ZipFile(srcApk)
            val zos = ZipOutputStream(FileOutputStream(destApk))
            val manifest = Manifest()
            manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            manifest.mainAttributes[Attributes.Name("Created-By")] = "1.0 (APatch)"

            val md = MessageDigest.getInstance("SHA-256")
            val oldPkgBytes = oldPkg.toByteArray(StandardCharsets.UTF_8)
            val newPkgBytes = newPkg.toByteArray(StandardCharsets.UTF_8)
            val oldPkgU16 = oldPkg.toByteArray(StandardCharsets.UTF_16LE)
            val newPkgU16 = newPkg.toByteArray(StandardCharsets.UTF_16LE)

            val entryMap = LinkedHashMap<String, ByteArray>()
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name

                // Skip old signature files
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".MF") || name.endsWith(".EC"))) {
                    continue
                }

                val raw = zipFile.getInputStream(entry).readBytes()
                val entryBytes = if (name == "AndroidManifest.xml") {
                    replaceBytes(raw, oldPkgBytes, newPkgBytes)
                    replaceBytes(raw, oldPkgU16, newPkgU16)
                    raw
                } else {
                    raw
                }
                entryMap[name] = entryBytes

                val digest = Base64.encodeToString(md.digest(entryBytes), Base64.NO_WRAP)
                val attr = Attributes()
                attr[Attributes.Name("SHA-256-Digest")] = digest
                manifest.entries[name] = attr
            }
            zipFile.close()

            // 1. Write META-INF/MANIFEST.MF first
            val mfBaos = ByteArrayOutputStream()
            manifest.write(mfBaos)
            val mfBytes = mfBaos.toByteArray()

            val mfEntry = ZipEntry("META-INF/MANIFEST.MF")
            zos.putNextEntry(mfEntry)
            zos.write(mfBytes)
            zos.closeEntry()

            // 2. Write META-INF/CERT.SF
            val sfBaos = ByteArrayOutputStream()
            sfBaos.write("Signature-Version: 1.0\r\nCreated-By: 1.0 (APatch)\r\n".toByteArray(StandardCharsets.UTF_8))
            val mfDigest = Base64.encodeToString(md.digest(mfBytes), Base64.NO_WRAP)
            sfBaos.write("SHA-256-Digest-Manifest: $mfDigest\r\n\r\n".toByteArray(StandardCharsets.UTF_8))

            for ((entryName, attr) in manifest.entries) {
                val chunk = "Name: $entryName\r\nSHA-256-Digest: ${attr.getValue("SHA-256-Digest")}\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
                val chunkDigest = Base64.encodeToString(md.digest(chunk), Base64.NO_WRAP)
                sfBaos.write("Name: $entryName\r\nSHA-256-Digest: $chunkDigest\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            }
            val sfBytes = sfBaos.toByteArray()

            val sfEntry = ZipEntry("META-INF/CERT.SF")
            zos.putNextEntry(sfEntry)
            zos.write(sfBytes)
            zos.closeEntry()

            // 3. Write META-INF/CERT.RSA (Valid PKCS#7 block)
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(privateKey)
            signer.update(sfBytes)
            val signatureBytes = signer.sign()

            val rsaBytes = createPkcs7Block(cert, signatureBytes)
            val rsaEntry = ZipEntry("META-INF/CERT.RSA")
            zos.putNextEntry(rsaEntry)
            zos.write(rsaBytes)
            zos.closeEntry()

            // 4. Write all APK contents
            for ((name, bytes) in entryMap) {
                val newEntry = ZipEntry(name)
                zos.putNextEntry(newEntry)
                zos.write(bytes)
                zos.closeEntry()
            }

            zos.flush()
            zos.close()
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "rebuildAndSignApk error: ", e)
            return false
        }
    }

    private fun loadSigningKeyAndCert(): Pair<PrivateKey, X509Certificate> {
        try {
            val keyStore = try {
                KeyStore.getInstance("PKCS12").apply {
                    apApp.assets.open("release.jks").use { load(it, "apatch123".toCharArray()) }
                }
            } catch (e: Exception) {
                KeyStore.getInstance("JKS").apply {
                    apApp.assets.open("release.jks").use { load(it, "apatch123".toCharArray()) }
                }
            }
            val pk = keyStore.getKey("apatch", "apatch123".toCharArray()) as PrivateKey
            val c = keyStore.getCertificate("apatch") as X509Certificate
            return pk to c
        } catch (e: Exception) {
            Log.w(TAG, "Keystore asset load fallback to runtime generator: ${e.message}")
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val pair = kpg.generateKeyPair()

            // Generate self-signed cert DER
            val certBytes = createSelfSignedCertDer(pair.public.encoded)
            val certFactory = CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            return pair.private to cert
        }
    }

    private fun createSelfSignedCertDer(publicKeyDer: ByteArray): ByteArray {
        val serial = derWrap(0x02, byteArrayOf(0x01))
        val algId = derSeq(derWrap(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x05)), byteArrayOf(0x05, 0x00))
        val name = derSeq(derSet(derSeq(derWrap(0x06, byteArrayOf(0x55, 0x04, 0x03)), derWrap(0x0C, "APatch".toByteArray()))))
        val validity = derSeq(derWrap(0x17, "200101000000Z".toByteArray()), derWrap(0x17, "500101000000Z".toByteArray()))
        val tbs = derSeq(serial, algId, name, validity, name, publicKeyDer)
        return derSeq(tbs, algId, derWrap(0x03, byteArrayOf(0x00, 0x01)))
    }

    private fun derLength(len: Int): ByteArray {
        return when {
            len < 128 -> byteArrayOf(len.toByte())
            len < 256 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 65536 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
            else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte())
        }
    }

    private fun derWrap(tag: Int, data: ByteArray): ByteArray {
        val len = derLength(data.size)
        val res = ByteArray(1 + len.size + data.size)
        res[0] = tag.toByte()
        System.arraycopy(len, 0, res, 1, len.size)
        System.arraycopy(data, 0, res, 1 + len.size, data.size)
        return res
    }

    private fun derSeq(vararg items: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        for (item in items) baos.write(item)
        return derWrap(0x30, baos.toByteArray())
    }

    private fun derSet(vararg items: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        for (item in items) baos.write(item)
        return derWrap(0x31, baos.toByteArray())
    }

    private fun createPkcs7Block(cert: X509Certificate, signature: ByteArray): ByteArray {
        val oidSignedData = derWrap(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02))
        val oidData = derWrap(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x01))
        val oidSha256 = derWrap(0x06, byteArrayOf(0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01))
        val oidRsa = derWrap(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01))
        val derNull = byteArrayOf(0x05, 0x00)

        val version = byteArrayOf(0x02, 0x01, 0x01)
        val algId = derSeq(oidSha256, derNull)
        val digestAlgorithms = derSet(algId)
        val encapContentInfo = derSeq(oidData)
        val certsTagged = derWrap(0xA0, cert.encoded)

        val signerVersion = byteArrayOf(0x02, 0x01, 0x01)
        val issuer = cert.issuerX500Principal.encoded
        val serial = derWrap(0x02, cert.serialNumber.toByteArray())
        val issuerAndSerial = derSeq(issuer, serial)
        val digestAlg = derSeq(oidSha256, derNull)
        val digestEncAlg = derSeq(oidRsa, derNull)
        val encDigest = derWrap(0x04, signature)

        val signerInfo = derSeq(signerVersion, issuerAndSerial, digestAlg, digestEncAlg, encDigest)
        val signerInfos = derSet(signerInfo)

        val signedData = derSeq(version, digestAlgorithms, encapContentInfo, certsTagged, signerInfos)
        return derSeq(oidSignedData, derWrap(0xA0, signedData))
    }

    private fun replaceBytes(source: ByteArray, target: ByteArray, replacement: ByteArray) {
        if (target.size != replacement.size) return
        var i = 0
        while (i <= source.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                System.arraycopy(replacement, 0, source, i, replacement.size)
                i += target.size
            } else {
                i++
            }
        }
    }
}
