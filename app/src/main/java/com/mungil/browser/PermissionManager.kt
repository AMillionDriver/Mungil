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
    
    // ✅ Android 10+ (API 29): MediaStore API (tidak perlu WRITE_EXTERNAL_STORAGE)
    // App sudah handle ini di NativeStreamDownloader via ContentResolver
    
    // ✅ Android 11+ (API 30): MANAGE_EXTERNAL_STORAGE untuk full access
    // (opsional, untuk app yang bukan media app)
    private val ALL_FILES_PERMISSION = arrayOf(
        Manifest.permission.MANAGE_EXTERNAL_STORAGE
    )
    
    // ✅ Android 13+ (API 33): Granular media permissions
    private val GRANULAR_MEDIA_PERMISSIONS = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO
    )

    /**
     * ✅ Cek apakah app punya semua permission yang diperlukan untuk download
     */
    fun hasDownloadPermissions(activity: Activity): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+: Cukup granular media permissions
                GRANULAR_MEDIA_PERMISSIONS.all {
                    ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11: Tidak perlu WRITE (MediaStore suffice)
                // Tapi bagus jika punya MANAGE_EXTERNAL_STORAGE untuk UI
                true
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10: MediaStore API (built-in, tidak perlu runtime permission)
                true
            }
            else -> {
                // Android 6-9: Perlu READ + WRITE
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+: Request granular media permissions
                GRANULAR_MEDIA_PERMISSIONS.filter {
                    ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
                }.toTypedArray()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11: Sudah pakai MediaStore, tapi bisa request MANAGE_EXTERNAL_STORAGE
                // untuk file browser access (opsional)
                emptyArray()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10: MediaStore built-in, tidak perlu request apapun
                emptyArray()
            }
            else -> {
                // Android 6-9: Request legacy permissions
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
        permissions: Array<String>,
        grantResults: IntArray,
        onPermissionResult: (Boolean) -> Unit
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            onPermissionResult(allGranted)
        }
    }

    /**
     * ✅ Get permission yang masih denied
     */
    fun getDeniedPermissions(activity: Activity): List<String> {
        val needed = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> GRANULAR_MEDIA_PERMISSIONS
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> emptyArray()
            else -> LEGACY_PERMISSIONS
        }
        
        return needed.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * ✅ Debug: Print permission status (untuk testing)
     */
    fun debugPermissionStatus(activity: Activity) {
        println("=== PERMISSION STATUS (API ${Build.VERSION.SDK_INT}) ===")
        
        val allPerms = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> GRANULAR_MEDIA_PERMISSIONS
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> emptyArray()
            else -> LEGACY_PERMISSIONS
        } + arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE)
        
        for (perm in allPerms) {
            val status = ContextCompat.checkSelfPermission(activity, perm)
            val granted = status == PackageManager.PERMISSION_GRANTED
            println("  $perm: ${if (granted) "✅ GRANTED" else "❌ DENIED"}")
        }
    }
}
