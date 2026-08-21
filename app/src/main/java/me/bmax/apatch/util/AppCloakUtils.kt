package me.bmax.apatch.util

import android.content.Context
import android.util.Log
import me.bmax.apatch.APApplication
import me.bmax.apatch.apApp
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

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
        try {
            val srcApk = File(apApp.applicationInfo.sourceDir)
            if (!srcApk.exists()) return false

            val tmpDir = File("/data/local/tmp")
            tmpDir.mkdirs()
            val targetApk = File(tmpDir, "apatch_cloaked.apk")
            if (targetApk.exists()) targetApk.delete()

            // 1. Copy base APK
            srcApk.copyTo(targetApk, overwrite = true)

            // 2. Patch package name inside target APK binary
            val currentPkgBytes = apApp.packageName.toByteArray(StandardCharsets.UTF_8)
            val newPkgBytes = newPkgName.toByteArray(StandardCharsets.UTF_8)

            val currentPkgBytesU16 = apApp.packageName.toByteArray(StandardCharsets.UTF_16LE)
            val newPkgBytesU16 = newPkgName.toByteArray(StandardCharsets.UTF_16LE)

            val raf = RandomAccessFile(targetApk, "rw")
            val bytes = ByteArray(raf.length().toInt())
            raf.readFully(bytes)

            replaceBytes(bytes, currentPkgBytes, newPkgBytes)
            replaceBytes(bytes, currentPkgBytesU16, newPkgBytesU16)

            raf.seek(0)
            raf.write(bytes)
            raf.close()

            // 3. Install the APK with root
            val installRes = rootShellForResult(
                "pm install -r -d '${targetApk.absolutePath}'"
            )
            Log.i(TAG, "Install result: ${installRes.isSuccess} ${installRes.out}")

            if (!installRes.isSuccess && !installRes.out.any { it.contains("Success", ignoreCase = true) }) {
                return false
            }

            // 4. Grant new UID in package_config & transfer settings
            val uidRes = rootShellForResult("pm list packages -U $newPkgName")
            val uidLine = uidRes.out.find { it.contains(newPkgName) }
            val newUid = Regex("uid:(\\d+)").find(uidLine ?: "")?.groupValues?.get(1)

            if (!newUid.isNullOrEmpty()) {
                rootShellForResult(
                    "echo '$newPkgName,0,1,$newUid,0,${APApplication.MAGISK_SCONTEXT}' >> ${APApplication.PACKAGE_CONFIG_FILE}",
                    "mkdir -p /data/data/$newPkgName/shared_prefs",
                    "cp -rf /data/data/${apApp.packageName}/shared_prefs/* /data/data/$newPkgName/shared_prefs/ 2>/dev/null || true",
                    "chown -R $newUid:$newUid /data/data/$newPkgName 2>/dev/null || true",
                    "killall -HUP apd 2>/dev/null || true"
                )
            }

            // 5. Launch new app and uninstall old app
            rootShellForResult(
                "am start -n '$newPkgName/me.bmax.apatch.ui.MainActivity'",
                "sleep 1",
                "pm uninstall '${apApp.packageName}' &"
            )

            targetApk.delete()
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "cloakApp error: ", e)
            return false
        }
    }

    fun restoreApp(): Boolean {
        try {
            val srcApk = File(apApp.applicationInfo.sourceDir)
            if (!srcApk.exists()) return false

            val tmpDir = File("/data/local/tmp")
            tmpDir.mkdirs()
            val targetApk = File(tmpDir, "apatch_original.apk")
            if (targetApk.exists()) targetApk.delete()

            srcApk.copyTo(targetApk, overwrite = true)

            val currentPkgBytes = apApp.packageName.toByteArray(StandardCharsets.UTF_8)
            val origPkgBytes = ORIGINAL_PKG.toByteArray(StandardCharsets.UTF_8)

            val currentPkgBytesU16 = apApp.packageName.toByteArray(StandardCharsets.UTF_16LE)
            val origPkgBytesU16 = ORIGINAL_PKG.toByteArray(StandardCharsets.UTF_16LE)

            val raf = RandomAccessFile(targetApk, "rw")
            val bytes = ByteArray(raf.length().toInt())
            raf.readFully(bytes)

            replaceBytes(bytes, currentPkgBytes, origPkgBytes)
            replaceBytes(bytes, currentPkgBytesU16, origPkgBytesU16)

            raf.seek(0)
            raf.write(bytes)
            raf.close()

            val installRes = rootShellForResult(
                "pm install -r -d '${targetApk.absolutePath}'"
            )

            if (!installRes.isSuccess && !installRes.out.any { it.contains("Success", ignoreCase = true) }) {
                return false
            }

            val uidRes = rootShellForResult("pm list packages -U $ORIGINAL_PKG")
            val uidLine = uidRes.out.find { it.contains(ORIGINAL_PKG) }
            val newUid = Regex("uid:(\\d+)").find(uidLine ?: "")?.groupValues?.get(1)

            if (!newUid.isNullOrEmpty()) {
                rootShellForResult(
                    "echo '$ORIGINAL_PKG,0,1,$newUid,0,${APApplication.MAGISK_SCONTEXT}' >> ${APApplication.PACKAGE_CONFIG_FILE}",
                    "mkdir -p /data/data/$ORIGINAL_PKG/shared_prefs",
                    "cp -rf /data/data/${apApp.packageName}/shared_prefs/* /data/data/$ORIGINAL_PKG/shared_prefs/ 2>/dev/null || true",
                    "chown -R $newUid:$newUid /data/data/$ORIGINAL_PKG 2>/dev/null || true",
                    "killall -HUP apd 2>/dev/null || true"
                )
            }

            rootShellForResult(
                "am start -n '$ORIGINAL_PKG/me.bmax.apatch.ui.MainActivity'",
                "sleep 1",
                "pm uninstall '${apApp.packageName}' &"
            )

            targetApk.delete()
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "restoreApp error: ", e)
            return false
        }
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
