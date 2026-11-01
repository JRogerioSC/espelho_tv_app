package com.rogerio.espelho_tv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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

    private lateinit var channel: MethodChannel
    private var pendingResult: MethodChannel.Result? = null
    private var pendingPort = 8080
    private var pendingQuality = 60
    private var pendingMaxWidth = 960
    private var pendingPin = ""
    private var pendingAudio = false

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)

        ScreenService.onStopped = {
            Handler(Looper.getMainLooper()).post { channel.invokeMethod("stopped", null) }
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
                    stepNotifications()
                }
                "stop" -> {
                    startService(Intent(this, ScreenService::class.java).setAction(ScreenService.ACTION_STOP))
                    result.success(true)
                }
                "isRunning" -> result.success(ScreenService.running)
                "audioActive" -> result.success(ScreenService.audioActive)
                "audioSupported" -> result.success(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "getIp" -> result.success(localIpAddress())
                else -> result.notImplemented()
            }
        }
    }

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
            stepAudioPermission()
        }
    }

    // Etapa 2: permissão de áudio (necessária para capturar o som interno).
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
            // Mesmo negando notificação ou áudio, seguimos: o espelhamento funciona sem eles.
            reqNotifications -> stepAudioPermission()
            reqAudio -> askScreenCapture()
        }
    }

    // Etapa 3: diálogo do sistema para permitir a captura de tela.
    private fun askScreenCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), reqProjection)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == reqProjection) {
            val result = pendingResult
            pendingResult = null
            if (resultCode == Activity.RESULT_OK && data != null) {
                val audioAllowed = pendingAudio &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED

                val intent = Intent(this, ScreenService::class.java).apply {
                    action = ScreenService.ACTION_START
                    putExtra(ScreenService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenService.EXTRA_DATA, data)
                    putExtra(ScreenService.EXTRA_PORT, pendingPort)
                    putExtra(ScreenService.EXTRA_QUALITY, pendingQuality)
                    putExtra(ScreenService.EXTRA_MAX_WIDTH, pendingMaxWidth)
                    putExtra(ScreenService.EXTRA_PIN, pendingPin)
                    putExtra(ScreenService.EXTRA_AUDIO, audioAllowed)
                }
                ContextCompat.startForegroundService(this, intent)
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
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
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