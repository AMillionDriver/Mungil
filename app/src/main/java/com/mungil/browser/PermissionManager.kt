package com.mungil.browser

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 🔐 Pengelola Permission Runtime untuk Download & File Access
 * Kompatibel: Android 6 (API 23) hingga Android 14+ (API 34+)
 */
object PermissionManager {
    const val PERMISSION_REQUEST_CODE = 101

    // ✅ Android 6-9 (API 23-28): Direct filesystem write
    private val LEGACY_PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    /**
     * ✅ Cek apakah app punya semua permission yang diperlukan untuk download
     */
    fun hasDownloadPermissions(activity: Activity): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10+ (API 29+): Scoped Storage / MediaStore API built-in
                true
            }
            else -> {
                // Android 6-9: Perlu izin runtime READ & WRITE_EXTERNAL_STORAGE
                LEGACY_PERMISSIONS.all {
                    ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
    }

    /**
     * ✅ Request permission yang diperlukan sesuai Android version
     */
    fun requestDownloadPermissions(activity: Activity) {
        val permissionsToRequest = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                emptyArray()
            }
            else -> {
                LEGACY_PERMISSIONS.filter {
                    ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
                }.toTypedArray()
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, permissionsToRequest, PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * ✅ Handle hasil permission request (panggil di MainActivity.onRequestPermissionsResult)
     */
    fun handlePermissionResult(
        requestCode: Int,
        grantResults: IntArray,
        onPermissionResult: (Boolean) -> Unit
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            onPermissionResult(allGranted)
        }
    }
}
