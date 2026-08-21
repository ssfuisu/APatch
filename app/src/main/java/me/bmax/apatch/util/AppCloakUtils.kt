package me.bmax.apatch.util

import android.util.Base64
import android.util.Log
import me.bmax.apatch.APApplication
import me.bmax.apatch.apApp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
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
        // Must be exact 14 chars to match "me.bmax.apatch"
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rand = (1..3).map { chars.random() }.joinToString("")
        return "com.sys.app$rand" // total 14 chars!
    }

    fun cloakApp(newPkgName: String): Boolean {
        return repackageAndInstall(apApp.packageName, newPkgName)
    }

    fun restoreApp(): Boolean {
        return repackageAndInstall(apApp.packageName, ORIGINAL_PKG)
    }

    private fun repackageAndInstall(currentPkg: String, targetPkg: String): Boolean {
        try {
            val srcApk = File(apApp.applicationInfo.sourceDir)
            if (!srcApk.exists()) {
                Log.e(TAG, "Source APK not found at ${srcApk.absolutePath}")
                return false
            }

            val tmpDir = File("/data/local/tmp")
            tmpDir.mkdirs()
            val outApk = File(tmpDir, "apatch_repackaged.apk")
            if (outApk.exists()) outApk.delete()

            // 1. Rebuild and Sign APK with new package name
            val repackSuccess = rebuildAndSignApk(srcApk, outApk, currentPkg, targetPkg)
            if (!repackSuccess || !outApk.exists()) {
                Log.e(TAG, "Rebuild and sign APK failed")
                return false
            }

            // 2. Install the repackaged APK with root
            val installRes = rootShellForResult(
                "pm install -r -d -g '${outApk.absolutePath}'"
            )
            Log.i(TAG, "pm install output: ${installRes.out} ${installRes.err}")

            val installOutput = (installRes.out + installRes.err).joinToString(" ")
            if (!installOutput.contains("Success", ignoreCase = true) && !installRes.isSuccess) {
                Log.e(TAG, "pm install failed: $installOutput")
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
            val keyStore = KeyStore.getInstance("JKS")
            val ksStream = apApp.assets.open("release.jks")
            keyStore.load(ksStream, "apatch123".toCharArray())
            ksStream.close()

            val privateKey = keyStore.getKey("apatch", "apatch123".toCharArray()) as PrivateKey
            val cert = keyStore.getCertificate("apatch") as X509Certificate

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

            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name

                // Skip existing signature files
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".MF"))) {
                    continue
                }

                val entryBytes = if (name == "AndroidManifest.xml") {
                    val raw = zipFile.getInputStream(entry).readBytes()
                    replaceBytes(raw, oldPkgBytes, newPkgBytes)
                    replaceBytes(raw, oldPkgU16, newPkgU16)
                    raw
                } else {
                    zipFile.getInputStream(entry).readBytes()
                }

                // Add to manifest
                val digest = Base64.encodeToString(md.digest(entryBytes), Base64.NO_WRAP)
                val attr = Attributes()
                attr[Attributes.Name("SHA-256-Digest")] = digest
                manifest.entries[name] = attr

                val newEntry = ZipEntry(name)
                zos.putNextEntry(newEntry)
                zos.write(entryBytes)
                zos.closeEntry()
            }

            // Write META-INF/MANIFEST.MF
            val mfBaos = ByteArrayOutputStream()
            manifest.write(mfBaos)
            val mfBytes = mfBaos.toByteArray()

            val mfEntry = ZipEntry("META-INF/MANIFEST.MF")
            zos.putNextEntry(mfEntry)
            zos.write(mfBytes)
            zos.closeEntry()

            // Write META-INF/CERT.SF
            val sfBaos = ByteArrayOutputStream()
            sfBaos.write("Signature-Version: 1.0\r\n".toByteArray(StandardCharsets.UTF_8))
            sfBaos.write("Created-By: 1.0 (APatch)\r\n".toByteArray(StandardCharsets.UTF_8))
            sfBaos.write("SHA-256-Digest-Manifest: ${Base64.encodeToString(md.digest(mfBytes), Base64.NO_WRAP)}\r\n\r\n".toByteArray(StandardCharsets.UTF_8))

            for ((entryName, attr) in manifest.entries) {
                val entryMfChunk = "Name: $entryName\r\nSHA-256-Digest: ${attr.getValue("SHA-256-Digest")}\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
                val chunkDigest = Base64.encodeToString(md.digest(entryMfChunk), Base64.NO_WRAP)
                sfBaos.write("Name: $entryName\r\n".toByteArray(StandardCharsets.UTF_8))
                sfBaos.write("SHA-256-Digest: $chunkDigest\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            }
            val sfBytes = sfBaos.toByteArray()

            val sfEntry = ZipEntry("META-INF/CERT.SF")
            zos.putNextEntry(sfEntry)
            zos.write(sfBytes)
            zos.closeEntry()

            // Write META-INF/CERT.RSA (Signature Block)
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(privateKey)
            signer.update(sfBytes)
            val signatureBytes = signer.sign()

            // Create PKCS#7 block wrapping cert and signature
            val rsaEntry = ZipEntry("META-INF/CERT.RSA")
            zos.putNextEntry(rsaEntry)
            zos.write(createPkcs7Block(cert, signatureBytes))
            zos.closeEntry()

            zos.flush()
            zos.close()
            zipFile.close()
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "rebuildAndSignApk error: ", e)
            return false
        }
    }

    private fun createPkcs7Block(cert: X509Certificate, signature: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(cert.encoded)
        baos.write(signature)
        return baos.toByteArray()
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
