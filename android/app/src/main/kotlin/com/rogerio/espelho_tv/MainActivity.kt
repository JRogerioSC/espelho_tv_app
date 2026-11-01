package com.rogerio.espelho_tv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : FlutterActivity() {

    private val channelName = "espelho_tv/channel"
    private val reqProjection = 1001
    private val reqNotifications = 1002
    private val reqAudio = 1003
    private val reqPick = 1004

    private lateinit var channel: MethodChannel
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingResult: MethodChannel.Result? = null
    private var pendingPort = 8080
    private var pendingQuality = 60
    private var pendingMaxWidth = 960
    private var pendingPin = ""
    private var pendingAudio = false
    private var pendingScreen = true

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)

        ScreenService.onStopped = {
            mainHandler.post { channel.invokeMethod("stopped", null) }
        }

        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "start" -> {
                    if (pendingResult != null) {
                        result.error("BUSY", "Já existe uma solicitação em andamento", null)
                        return@setMethodCallHandler
                    }
                    pendingResult = result
                    pendingPort = call.argument<Int>("port") ?: 8080
                    pendingQuality = call.argument<Int>("quality") ?: 60
                    pendingMaxWidth = call.argument<Int>("maxWidth") ?: 960
                    pendingPin = call.argument<String>("pin") ?: ""
                    pendingAudio = call.argument<Boolean>("audio") ?: false
                    pendingScreen = call.argument<Boolean>("screen") ?: true
                    stepNotifications()
                }
                "stop" -> {
                    startService(Intent(this, ScreenService::class.java).setAction(ScreenService.ACTION_STOP))
                    result.success(true)
                }
                "isRunning" -> result.success(ScreenService.running)
                "screenActive" -> result.success(ScreenService.screenMode)
                "audioActive" -> result.success(ScreenService.audioActive)
                "audioSupported" -> result.success(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "getIp" -> result.success(localIpAddress())

                // ----- Biblioteca de filmes -----
                "libraryList" -> {
                    result.success(
                        LibraryStore.list(this).map {
                            mapOf("name" to it.name, "size" to it.length())
                        }
                    )
                }
                "libraryPath" -> result.success(LibraryStore.dir(this).absolutePath)
                "libraryDelete" -> {
                    val name = call.argument<String>("name")
                    val file = if (name == null) null else LibraryStore.find(this, name)
                    result.success(file?.delete() == true)
                }
                "libraryAdd" -> {
                    pickMovies()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    // ---------- Fluxo de permissões para iniciar ----------

    // Etapa 1: permissão de notificação (Android 13+), para mostrar o botão "Parar".
    private fun stepNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotifications
            )
        } else {
            afterNotifications()
        }
    }

    private fun afterNotifications() {
        if (pendingScreen) stepAudioPermission() else startLibraryOnly()
    }

    // Etapa 2 (só espelhando a tela): permissão de áudio, para capturar o som interno.
    private fun stepAudioPermission() {
        if (pendingAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), reqAudio
            )
        } else {
            askScreenCapture()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            // Mesmo negando notificação ou áudio, seguimos: funciona sem eles.
            reqNotifications -> afterNotifications()
            reqAudio -> askScreenCapture()
        }
    }

    // Etapa 3 (só espelhando a tela): diálogo do sistema para permitir a captura.
    private fun askScreenCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), reqProjection)
    }

    /** Modo "só filmes": o servidor sobe sem capturar a tela. */
    private fun startLibraryOnly() {
        val result = pendingResult
        pendingResult = null
        launchService(0, null, false)
        result?.success(
            mapOf("ok" to true, "audioRequested" to false, "audioPermission" to false)
        )
    }

    private fun launchService(resultCode: Int, data: Intent?, audioAllowed: Boolean) {
        val intent = Intent(this, ScreenService::class.java).apply {
            action = ScreenService.ACTION_START
            putExtra(ScreenService.EXTRA_RESULT_CODE, resultCode)
            if (data != null) putExtra(ScreenService.EXTRA_DATA, data)
            putExtra(ScreenService.EXTRA_PORT, pendingPort)
            putExtra(ScreenService.EXTRA_QUALITY, pendingQuality)
            putExtra(ScreenService.EXTRA_MAX_WIDTH, pendingMaxWidth)
            putExtra(ScreenService.EXTRA_PIN, pendingPin)
            putExtra(ScreenService.EXTRA_AUDIO, audioAllowed)
            putExtra(ScreenService.EXTRA_SCREEN, pendingScreen)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    // ---------- Escolher e copiar filmes ----------

    private fun pickMovies() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, reqPick)
    }

    private fun post(method: String, args: Map<String, Any?>) {
        mainHandler.post {
            try {
                channel.invokeMethod(method, args)
            } catch (_: Exception) {
            }
        }
    }

    private fun copyMovies(uris: List<Uri>) {
        Thread {
            var ok = 0
            val failed = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                val label = LibraryStore.displayNameAndSize(this, uri).first
                try {
                    LibraryStore.importOne(this, uri) { copied, size ->
                        post(
                            "copyProgress",
                            mapOf(
                                "name" to label,
                                "index" to i + 1,
                                "total" to uris.size,
                                "copied" to copied,
                                "size" to size
                            )
                        )
                    }
                    ok++
                } catch (e: Exception) {
                    failed.add(label + " (" + (e.message ?: "erro") + ")")
                }
            }
            post("copyDone", mapOf("ok" to ok, "failed" to failed))
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            reqProjection -> {
                val result = pendingResult
                pendingResult = null
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val audioAllowed = pendingAudio &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    launchService(resultCode, data, audioAllowed)
                    result?.success(
                        mapOf(
                            "ok" to true,
                            "audioRequested" to pendingAudio,
                            "audioPermission" to audioAllowed
                        )
                    )
                } else {
                    result?.success(mapOf("ok" to false))
                }
            }
            reqPick -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val uris = mutableListOf<Uri>()
                    val clip = data.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                    } else if (data.data != null) {
                        uris.add(data.data!!)
                    }
                    if (uris.isNotEmpty()) copyMovies(uris)
                }
            }
            else -> {
                @Suppress("DEPRECATION")
                super.onActivityResult(requestCode, resultCode, data)
            }
        }
    }

    /** Procura o IPv4 da rede local (Wi-Fi ou hotspot). */
    private fun localIpAddress(): String? {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.inetAddresses.toList().map { ni.name to it } }
                .filter { (_, addr) -> addr is Inet4Address && addr.isSiteLocalAddress }
            // Prefere wlan/ap (Wi-Fi ou hotspot) a interfaces de dados móveis.
            val preferred = candidates.firstOrNull { (name, _) ->
                name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
            } ?: candidates.firstOrNull()
            preferred?.second?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}