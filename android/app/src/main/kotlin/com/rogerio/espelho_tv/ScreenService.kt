package com.rogerio.espelho_tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Serviço em primeiro plano que serve, por HTTP na rede local e protegido por PIN:
 *  - a tela do celular (MediaProjection -> MJPEG), opcional;
 *  - o áudio interno (AudioPlaybackCapture, Android 10+), opcional;
 *  - a biblioteca de filmes do app (arquivos com suporte a Range, para avançar/voltar).
 * A TV só precisa abrir http://IP_DO_CELULAR:PORTA no navegador.
 */
class ScreenService : Service() {

    companion object {
        const val ACTION_START = "espelho.START"
        const val ACTION_STOP = "espelho.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_PORT = "port"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_MAX_WIDTH = "maxWidth"
        const val EXTRA_PIN = "pin"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_SCREEN = "screen"
        const val CHANNEL_ID = "espelho_tv_channel"
        const val NOTIFICATION_ID = 4711

        const val AUDIO_RATE = 44100
        const val AUDIO_CHANNELS = 2
        private const val AUDIO_CHUNK_BYTES = 8192 // ~46 ms de áudio estéreo

        @Volatile
        var running = false

        /** true = espelhando a tela; false = só servindo a biblioteca de filmes. */
        @Volatile
        var screenMode = true

        @Volatile
        var audioActive = false

        @Volatile
        var onStopped: (() -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var port = 8080
    private var quality = 60
    private var maxWidth = 960
    private var captureWidth = 0
    private var captureHeight = 0
    private var densityDpi = 320

    private val frameLock = Object()
    private var latestFrame: ByteArray? = null
    private var frameSeq = 0L
    private var lastFrameTime = 0L
    private val minFrameIntervalMs = 66L // ~15 quadros por segundo

    private var bitmap: Bitmap? = null

    // Segurança e conexões
    private var access: AccessControl? = null
    private var pageBytes: ByteArray = ByteArray(0)
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet())

    // Áudio
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    @Volatile
    private var audioRunning = false
    private val audioClients = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            captureHandler?.post { resizeCapture() }
        }

        override fun onLowMemory() {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
            ACTION_STOP -> {
                stopEverything()
            }
            ACTION_START -> {
                if (running) return START_NOT_STICKY
                port = intent.getIntExtra(EXTRA_PORT, 8080)
                quality = intent.getIntExtra(EXTRA_QUALITY, 60)
                maxWidth = intent.getIntExtra(EXTRA_MAX_WIDTH, 960)
                val wantAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)
                val screen = intent.getBooleanExtra(EXTRA_SCREEN, true)
                val pin = intent.getStringExtra(EXTRA_PIN) ?: ""
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)

                // Sem PIN válido, ou sem permissão de captura quando espelhando, não transmite.
                if ((screen && data == null) || !Regex("^[0-9]{4,8}$").matches(pin)) {
                    // O Android exige startForeground após startForegroundService.
                    startForegroundCompat(false)
                    stopEverything()
                    return START_NOT_STICKY
                }

                screenMode = screen
                access = AccessControl(pin)
                pageBytes = try {
                    assets.open("player.html").use { it.readBytes() }
                } catch (e: Exception) {
                    "<h1>player.html ausente</h1>".toByteArray()
                }

                startForegroundCompat(screen)
                if (screen && data != null) {
                    startCapture(resultCode, data)
                    if (wantAudio) startAudioCapture()
                }
                startServer()
                running = true
            }
        }
        return START_NOT_STICKY
    }

    // ---------- Notificação ----------

    private fun startForegroundCompat(screen: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transmissão para a TV", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, ScreenService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        val notification = builder
            .setContentTitle(if (screen) "Espelhando a tela na TV" else "Servindo filmes para a TV")
            .setContentText("Toque para abrir o app ou use Parar")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPending)
            .addAction(
                @Suppress("DEPRECATION")
                Notification.Action.Builder(android.R.drawable.ic_media_pause, "Parar", stopPending).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (screen)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------- Captura de tela ----------

    private fun computeSize() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        densityDpi = metrics.densityDpi
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        // Reduz para economizar banda; mantém proporção; largura/altura pares.
        val longSide = maxOf(w, h)
        if (longSide > maxWidth) {
            val scale = maxWidth.toFloat() / longSide
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        captureWidth = w and 1.inv()
        captureHeight = h and 1.inv()
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        captureThread = HandlerThread("captura-tela").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        // Obrigatório no Android 14+: registrar callback antes de criar o display.
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopEverything()
            }
        }, captureHandler)

        computeSize()
        imageReader = createReader()
        virtualDisplay = projection?.createVirtualDisplay(
            "EspelhoTV",
            captureWidth, captureHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, captureHandler
        )
        registerComponentCallbacks(configCallbacks)
    }

    private fun createReader(): ImageReader {
        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (image == null) return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameTime < minFrameIntervalMs) return@setOnImageAvailableListener
                lastFrameTime = now

                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width
                val fullWidth = image.width + rowPadding / pixelStride

                var bmp = bitmap
                if (bmp == null || bmp.width != fullWidth || bmp.height != image.height) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(fullWidth, image.height, Bitmap.Config.ARGB_8888)
                    bitmap = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer)

                val out = ByteArrayOutputStream(64 * 1024)
                // Comprime só a área visível (descarta o padding da linha).
                val visible = if (fullWidth != image.width)
                    Bitmap.createBitmap(bmp, 0, 0, image.width, image.height) else bmp
                visible.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (visible !== bmp) visible.recycle()

                synchronized(frameLock) {
                    latestFrame = out.toByteArray()
                    frameSeq++
                    frameLock.notifyAll()
                }
            } catch (e: Exception) {
                // ignora quadro com erro
            } finally {
                image.close()
            }
        }, captureHandler)
        return reader
    }

    private fun resizeCapture() {
        if (virtualDisplay == null) return
        computeSize()
        val old = imageReader
        val newReader = createReader()
        imageReader = newReader
        bitmap?.recycle()
        bitmap = null
        virtualDisplay?.resize(captureWidth, captureHeight, densityDpi)
        virtualDisplay?.surface = newReader.surface
        old?.close()
    }

    // ---------- Captura de áudio interno ----------

    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val proj = projection ?: return
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, AUDIO_CHUNK_BYTES) * 2)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return
            }
            audioRecord = rec
            audioRunning = true
            audioActive = true
            audioThread = Thread {
                val buf = ByteArray(AUDIO_CHUNK_BYTES)
                try {
                    rec.startRecording()
                    while (audioRunning) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) broadcastAudio(buf.copyOf(n)) else if (n < 0) break
                    }
                } catch (e: Exception) {
                    // encerra a captura de áudio; a imagem continua
                } finally {
                    audioActive = false
                }
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            // Sem permissão ou aparelho sem suporte: segue só com imagem.
            audioActive = false
        }
    }

    private fun broadcastAudio(chunk: ByteArray) {
        for (q in audioClients) {
            // Fila cheia = cliente lento: descarta o mais antigo para não acumular atraso.
            if (!q.offer(chunk)) {
                q.poll()
                q.offer(chunk)
            }
        }
    }

    // ---------- Servidor HTTP ----------

    private fun startServer() {
        serverThread = Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                while (!Thread.currentThread().isInterrupted) {
                    val client = ss.accept()
                    Thread { handleClient(client) }.apply { isDaemon = true }.start()
                }
            } catch (e: BindException) {
                // Porta ocupada: não há como transmitir.
                stopEverything()
            } catch (e: Exception) {
                // servidor encerrado
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handleClient(socket: Socket) {
        clients.add(socket)
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000 // limite só para ler o pedido
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: return
            val target = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            var range: String? = null
            var headerCount = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                headerCount++
                if (headerCount > 64) return
                if (line.startsWith("content-length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                } else if (line.startsWith("range:", ignoreCase = true)) {
                    range = line.substringAfter(':').trim()
                }
            }

            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val out = socket.getOutputStream()
            val ip = socket.inetAddress?.hostAddress ?: "?"

            when {
                method == "GET" && path == "/" -> servePage(out)

                method == "POST" && path == "/auth" -> {
                    if (contentLength !in 0..64) {
                        respond(out, 413, "Payload Too Large", "text/plain", ByteArray(0))
                    } else {
                        handleAuth(out, ip, readBody(input, contentLength))
                    }
                }

                method == "GET" && path == "/stream" -> {
                    if (!authorized(query)) {
                        deny(out)
                    } else if (!screenMode) {
                        respond(out, 404, "Not Found", "text/plain", ByteArray(0))
                    } else {
                        socket.soTimeout = 0
                        streamMjpeg(out)
                    }
                }

                method == "GET" && path == "/audio" -> {
                    if (!authorized(query)) {
                        deny(out)
                    } else {
                        socket.soTimeout = 0
                        streamAudio(out)
                    }
                }

                method == "GET" && path == "/library" -> {
                    if (!authorized(query)) deny(out) else serveLibrary(out)
                }

                method == "GET" && path == "/video" -> {
                    if (!authorized(query)) {
                        deny(out)
                    } else {
                        socket.soTimeout = 0
                        streamVideo(out, query, range)
                    }
                }

                else -> respond(out, 404, "Not Found", "text/plain", "Nao encontrado".toByteArray())
            }
        } catch (e: Exception) {
            // cliente desconectou ou pedido inválido
        } finally {
            clients.remove(socket)
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readBody(input: BufferedReader, len: Int): String {
        val buf = CharArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun queryParam(query: String, key: String): String? =
        query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == key }
            ?.getOrNull(1)

    private fun authorized(query: String): Boolean =
        access?.isValid(queryParam(query, "t")) == true

    private fun deny(out: OutputStream) {
        respond(out, 401, "Unauthorized", "text/plain", ByteArray(0))
    }

    private fun handleAuth(out: OutputStream, ip: String, attempt: String) {
        val ac = access
        if (ac == null) {
            respond(out, 503, "Service Unavailable", "text/plain", ByteArray(0))
            return
        }
        when (val r = ac.login(ip, attempt.trim())) {
            is AccessControl.Result.Ok -> {
                val json = "{\"t\":\"${r.token}\",\"audio\":$audioActive,\"screen\":$screenMode," +
                    "\"rate\":$AUDIO_RATE,\"ch\":$AUDIO_CHANNELS}"
                respond(out, 200, "OK", "application/json", json.toByteArray())
            }
            is AccessControl.Result.Locked -> {
                respond(out, 429, "Too Many Requests", "application/json",
                    "{\"wait\":${r.seconds}}".toByteArray())
            }
            else -> respond(out, 403, "Forbidden", "application/json", "{\"error\":\"pin\"}".toByteArray())
        }
    }

    private fun respond(out: OutputStream, code: Int, status: String, type: String, body: ByteArray) {
        val header = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun servePage(out: OutputStream) {
        respond(out, 200, "OK", "text/html; charset=utf-8", pageBytes)
    }

    // ---------- Biblioteca de filmes ----------

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun serveLibrary(out: OutputStream) {
        val sb = StringBuilder("{\"files\":[")
        LibraryStore.list(this).forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"n\":").append(jsonString(f.name))
                .append(",\"s\":").append(f.length()).append('}')
        }
        sb.append("]}")
        respond(out, 200, "OK", "application/json; charset=utf-8", sb.toString().toByteArray(Charsets.UTF_8))
    }

    /** Entrega o arquivo de vídeo, com suporte a "Range" (necessário para avançar e voltar). */
    private fun streamVideo(out: OutputStream, query: String, range: String?) {
        val name = queryParam(query, "f")?.let { URLDecoder.decode(it, "UTF-8") }
        val file = if (name == null) null else LibraryStore.find(this, name)
        if (file == null) {
            respond(out, 404, "Not Found", "text/plain", ByteArray(0))
            return
        }

        val total = file.length()
        var start = 0L
        var end = total - 1
        var partial = false

        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',').trim()
            val a = spec.substringBefore('-')
            val b = spec.substringAfter('-', "")
            if (a.isEmpty()) {
                // "bytes=-N": os últimos N bytes
                val n = b.toLongOrNull()
                if (n != null && n > 0) {
                    start = maxOf(0L, total - n)
                    partial = true
                }
            } else {
                val s = a.toLongOrNull()
                if (s != null) {
                    start = s
                    if (b.isNotEmpty()) {
                        val e = b.toLongOrNull()
                        if (e != null) end = minOf(e, total - 1)
                    }
                    partial = true
                }
            }
            if (partial && (start >= total || start > end)) {
                out.write(
                    ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                )
                out.flush()
                return
            }
        }

        val length = end - start + 1
        val header = StringBuilder()
        header.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        header.append("Content-Type: ").append(LibraryStore.mimeOf(file)).append("\r\n")
        header.append("Accept-Ranges: bytes\r\n")
        header.append("Content-Length: ").append(length).append("\r\n")
        if (partial) header.append("Content-Range: bytes ").append(start).append('-')
            .append(end).append('/').append(total).append("\r\n")
        header.append("Connection: close\r\n\r\n")
        out.write(header.toString().toByteArray())

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0 && running) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
            out.flush()
        }
    }

    // ---------- Tela e áudio ao vivo ----------

    private fun streamMjpeg(out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-store, no-cache\r\nPragma: no-cache\r\nConnection: close\r\n\r\n").toByteArray()
        )
        var lastSent = -1L
        while (running) {
            var current: ByteArray? = null
            synchronized(frameLock) {
                while (frameSeq == lastSent && running) {
                    frameLock.wait(1000)
                }
                lastSent = frameSeq
                current = latestFrame
            }
            val frame = current ?: continue
            out.write(
                ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n").toByteArray()
            )
            out.write(frame)
            out.write("\r\n".toByteArray())
            out.flush()
        }
    }

    private fun streamAudio(out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.flush()
        val queue = LinkedBlockingQueue<ByteArray>(40)
        audioClients.add(queue)
        try {
            while (running) {
                val chunk = queue.poll(1, TimeUnit.SECONDS) ?: continue
                out.write(chunk)
                out.flush()
            }
        } finally {
            audioClients.remove(queue)
        }
    }

    // ---------- Encerramento ----------

    private fun stopEverything() {
        val wasRunning = running
        running = false
        audioRunning = false
        audioActive = false
        synchronized(frameLock) { frameLock.notifyAll() }
        try { unregisterComponentCallbacks(configCallbacks) } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        serverThread?.interrupt()
        synchronized(clients) {
            for (c in clients) {
                try { c.close() } catch (_: Exception) {}
            }
            clients.clear()
        }
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioThread?.interrupt()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        captureThread?.quitSafely()
        bitmap?.recycle()
        bitmap = null
        virtualDisplay = null
        imageReader = null
        projection = null
        serverSocket = null
        audioRecord = null
        audioThread = null
        access = null
        audioClients.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
        if (wasRunning) onStopped?.invoke()
    }

    override fun onDestroy() {
        if (running) stopEverything()
        super.onDestroy()
    }
}