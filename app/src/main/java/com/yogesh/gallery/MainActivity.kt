package com.yogesh.gallery

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

class GalleryApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(VideoFrameDecoder.Factory())
            add(ThumbFetcher.Factory(this@GalleryApp))
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.30).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizePercent(0.02).build() }
        .allowRgb565(true)
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()
}

class MainActivity : FragmentActivity() {
    private val vm: GalleryVM by viewModels()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.hasPermission = hasMediaPermission()
        vm.reload()
    }

    private val grantLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == RESULT_OK) vm.pendingAfter?.invoke()
        vm.pendingAfter = null
        vm.reload()
    }

    private val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
            }
            vm.backupTo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the status/navigation bar icons must be light (otherwise they vanish on light-theme phones).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        vm.launchSender = { sender, after ->
            vm.pendingAfter = after
            grantLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
        vm.authenticate = { title, onOk -> authenticate(title, onOk) }
        vm.pickBackupFolder = { treeLauncher.launch(null) }
        vm.hasPermission = hasMediaPermission()

        setContent { GalleryTheme { App(vm) { permLauncher.launch(requiredPerms()) } } }

        applyHighRefreshRate()
        if (vm.hasPermission) vm.reload() else permLauncher.launch(requiredPerms())
        if (vm.locked) authenticate("Unlock Gallery") { vm.locked = false }
    }

    override fun onResume() {
        super.onResume()
        vm.hasPermission = hasMediaPermission()
        if (vm.hasPermission) vm.reload()
    }

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)

    private fun hasMediaPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    /** Ask the system to run the screen at its highest refresh rate (90/120 Hz) for buttery scrolling. */
    private fun applyHighRefreshRate() {
        try {
            val d = display ?: return
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            lp.preferredRefreshRate = best.refreshRate
            window.attributes = lp
        } catch (e: Exception) {
        }
    }

    private fun authenticate(title: String, onOk: () -> Unit) {
        val auth = Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(auth)
            .build()
        try { prompt.authenticate(info) } catch (e: Exception) { onOk() }
    }
}
