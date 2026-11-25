package com.example.cctv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

private object FrameStore {
    @Volatile var jpeg: ByteArray? = null
    @Volatile var sequence = 0L
    @Volatile var lastMotion = 0L
    val lock = Object()

    fun publish(frame: ByteArray) = synchronized(lock) {
        jpeg = frame
        sequence++
        lock.notifyAll()
    }
}

private class MjpegStream : InputStream() {
    private var buffer = ByteArray(0)
    private var position = 0
    private var lastSequence = -1L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(out: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= buffer.size) {
            val frame = synchronized(FrameStore.lock) {
                while (FrameStore.sequence == lastSequence || FrameStore.jpeg == null) {
                    FrameStore.lock.wait(2_000)
                }
                lastSequence = FrameStore.sequence
                FrameStore.jpeg
            } ?: return 0
            val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray()
            buffer = header + frame + "\r\n".toByteArray()
            position = 0
        }
        val count = minOf(length, buffer.size - position)
        System.arraycopy(buffer, position, out, offset, count)
        position += count
        return count
    }
}

private class CameraServer(private val password: String) : NanoHTTPD(8080) {
    override fun serve(session: IHTTPSession): Response {
        val supplied = session.parameters["pw"]?.firstOrNull().orEmpty()
        if (supplied != password) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Access denied")
        }
        return when (session.uri) {
            "/stream" -> newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=frame",
                MjpegStream()
            )
            "/status" -> {
                val moving = FrameStore.lastMotion > 0 && System.currentTimeMillis() - FrameStore.lastMotion < 3_000
                newFixedLengthResponse(Response.Status.OK, "text/plain", if (moving) "MOTION" else "OK")
            }
            else -> newFixedLengthResponse(Response.Status.OK, "text/plain", "Local CCTV camera is running")
        }
    }
}

class MainActivity : ComponentActivity() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val viewerExecutor = Executors.newSingleThreadExecutor()
    private var server: CameraServer? = null
    @Volatile private var viewerRunning = false
    private var pendingCameraStart: (() -> Unit)? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingCameraStart?.invoke() else toast("Camera permission is needed to start camera mode.")
        pendingCameraStart = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(36), dp(24), dp(24))
        setBackgroundColor(Color.rgb(10, 16, 29))
    }

    private fun heading(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(18))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 29f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 15f
                setTextColor(Color.rgb(163, 177, 201))
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setOnClickListener { onClick() }
    }

    private fun showHome() {
        viewerRunning = false
        val root = page()
        root.addView(heading("LOCAL CCTV", "Turn an Android phone into a camera on your local network."))
        root.addView(TextView(this).apply {
            text = "●  PRIVATE BY DESIGN\n\nLive video stays on your local Wi-Fi or phone hotspot. This app does not record video or upload it to a cloud service."
            textSize = 15f
            setTextColor(Color.rgb(214, 224, 240))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(24, 34, 52))
        })
        root.addView(actionButton("Set up this phone as a camera") { showCameraSetup() }, spacedParams())
        root.addView(actionButton("View another camera") { showViewerSetup() }, spacedParams())
        root.addView(TextView(this).apply {
            text = "Use only where you have permission to monitor. Both phones must be on the same trusted network."
            textSize = 13f
            setTextColor(Color.rgb(163, 177, 201))
            setPadding(0, dp(22), 0, 0)
        })
        setContentView(root)
    }

    private fun spacedParams() = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(14) }

    private fun showCameraSetup() {
        val root = page()
        root.addView(heading("Camera setup", "Keep this phone awake while streaming."))
        root.addView(TextView(this).apply {
            text = "Create a password with at least 8 characters. The viewer will need this password to connect."
            textSize = 15f
            setTextColor(Color.rgb(214, 224, 240))
        })
        val password = EditText(this).apply {
            hint = "Camera password (8+ characters)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        root.addView(password, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(14) })
        root.addView(actionButton("Start camera") {
            val secret = password.text.toString()
            if (secret.length < 8) {
                toast("Choose a password with at least 8 characters.")
            } else {
                requestCamera { startCamera(secret) }
            }
        }, spacedParams())
        root.addView(actionButton("Back") { showHome() }, spacedParams())
        setContentView(root)
    }

    private fun requestCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingCameraStart = action
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun localAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                val items = network.inetAddresses
                while (items.hasMoreElements()) {
                    val address = items.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        addresses.add(address.hostAddress ?: "")
                    }
                }
            }
        } catch (_: Exception) { }
        return addresses.distinct()
    }

    private fun startCamera(password: String) {
        server?.stop()
        try {
            server = CameraServer(password).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        } catch (error: Exception) {
            server = null
            toast("Could not start the local stream on port 8080: ${error.message ?: "port unavailable"}")
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ips = localAddresses()
        val root = page()
        root.addView(heading("Camera is live", "LOCAL STREAM  •  PORT 8080"))
        root.addView(TextView(this).apply {
            text = if (ips.isEmpty()) "No local IPv4 address found. Connect to Wi-Fi or start a hotspot, then try again."
            else "On the viewer phone, enter one of these addresses:\n\n${ips.joinToString("\n")}\n\nPassword: the one you just created"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(24, 34, 52))
        })
        val status = TextView(this).apply {
            text = "Starting camera…"
            textSize = 14f
            setTextColor(Color.rgb(163, 220, 180))
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(status)
        root.addView(actionButton("Stop camera") { stopCamera(); showHome() }, spacedParams())
        setContentView(root)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var previousSamples: IntArray? = null
                var lastFrameAt = 0L
                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        val now = System.currentTimeMillis()
                        if (now - lastFrameAt >= 120) {
                            lastFrameAt = now
                            val nv21 = toNv21(image)
                            val width = image.width
                            val height = image.height
                            val samples = sampleLuma(nv21, width, height)
                            previousSamples?.let { previous ->
                                var changed = 0
                                for (index in samples.indices) {
                                    if (kotlin.math.abs(samples[index] - previous[index]) > 30) changed++
                                }
                                if (samples.isNotEmpty() && changed > samples.size * 0.025) {
                                    FrameStore.lastMotion = now
                                }
                            }
                            previousSamples = samples
                            val jpeg = ByteArrayOutputStream()
                            YuvImage(nv21, ImageFormat.NV21, width, height, null)
                                .compressToJpeg(Rect(0, 0, width, height), 72, jpeg)
                            var bytes = jpeg.toByteArray()
                            val rotation = image.imageInfo.rotationDegrees
                            if (rotation != 0) {
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    val rotated = Bitmap.createBitmap(
                                        bitmap, 0, 0, bitmap.width, bitmap.height,
                                        Matrix().apply { postRotate(rotation.toFloat()) }, true
                                    )
                                    val output = ByteArrayOutputStream()
                                    rotated.compress(Bitmap.CompressFormat.JPEG, 72, output)
                                    bytes = output.toByteArray()
                                    if (rotated !== bitmap) rotated.recycle()
                                    bitmap.recycle()
                                }
                            }
                            FrameStore.publish(bytes)
                        }
                    } catch (_: Exception) {
                        // Drop a bad frame; the next camera frame can still be streamed.
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                runOnUiThread { status.text = "● Camera active — video is not being recorded." }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Camera could not start: ${error.message ?: "check camera permission"}"
                    server?.stop()
                    server = null
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sampleLuma(nv21: ByteArray, width: Int, height: Int): IntArray {
        val step = 8
        val result = IntArray((width / step) * (height / step))
        var index = 0
        for (y in 0 until height step step) {
            for (x in 0 until width step step) result[index++] = nv21[y * width + x].toInt() and 0xff
        }
        return result
    }

    private fun toNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val output = ByteArray(width * height * 3 / 2)
        var position = 0
        for (row in 0 until height) {
            for (column in 0 until width) {
                output[position++] = yPlane.buffer.get(row * yPlane.rowStride + column * yPlane.pixelStride)
            }
        }
        for (row in 0 until height / 2) {
            for (column in 0 until width / 2) {
                output[position++] = vPlane.buffer.get(row * vPlane.rowStride + column * vPlane.pixelStride)
                output[position++] = uPlane.buffer.get(row * uPlane.rowStride + column * uPlane.pixelStride)
            }
        }
        return output
    }

    private fun showViewerSetup() {
        val root = page()
        root.addView(heading("Viewer setup", "Connect to a camera phone on the same network."))
        val address = EditText(this).apply {
            hint = "Camera IP address (for example 192.168.1.20)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val password = EditText(this).apply {
            hint = "Camera password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        root.addView(address, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(10) })
        root.addView(password, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
        root.addView(actionButton("Connect") {
            val host = address.text.toString().trim().removePrefix("http://").removePrefix("https://").substringBefore(":")
            val secret = password.text.toString()
            if (!host.matches(Regex("[A-Za-z0-9.-]{1,253}")) || secret.isBlank()) {
                toast("Enter a valid camera IP address and password.")
            } else {
                showViewer(host, secret)
            }
        }, spacedParams())
        root.addView(actionButton("Back") { showHome() }, spacedParams())
        setContentView(root)
    }

    private fun showViewer(host: String, password: String) {
        viewerRunning = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val status = TextView(this).apply {
            text = "Connecting to $host…"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(30), dp(14), dp(14))
            setBackgroundColor(Color.rgb(24, 34, 52))
        }
        val encoded = URLEncoder.encode(password, StandardCharsets.UTF_8.name())
        val video = WebView(this).apply {
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.javaScriptEnabled = false
            setBackgroundColor(Color.BLACK)
            loadUrl("http://$host:8080/stream?pw=$encoded")
        }
        root.addView(status)
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(actionButton("Disconnect") { viewerRunning = false; video.stopLoading(); video.destroy(); showHome() })
        setContentView(root)

        viewerExecutor.execute {
            var previouslyMoving = false
            while (viewerRunning) {
                try {
                    val connection = java.net.URL("http://$host:8080/status?pw=$encoded").openConnection().apply {
                        connectTimeout = 2_500
                        readTimeout = 2_500
                    }
                    val result = connection.getInputStream().bufferedReader().use { it.readText().trim() }
                    val moving = result == "MOTION"
                    runOnUiThread {
                        if (viewerRunning) {
                            status.text = if (moving) "⚠  MOTION DETECTED — $host" else "●  Connected — $host"
                            status.setBackgroundColor(if (moving) Color.rgb(150, 35, 40) else Color.rgb(24, 75, 52))
                        }
                    }
                    if (moving && !previouslyMoving) alertUser()
                    previouslyMoving = moving
                } catch (_: Exception) {
                    runOnUiThread {
                        if (viewerRunning) {
                            status.text = "Cannot connect. Check the IP, password, and same Wi-Fi network."
                            status.setBackgroundColor(Color.rgb(120, 45, 45))
                        }
                    }
                }
                try { Thread.sleep(2_000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun alertUser() {
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, 80).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    private fun stopCamera() {
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) { }
        server?.stop()
        server = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        viewerRunning = false
        stopCamera()
        cameraExecutor.shutdown()
        viewerExecutor.shutdownNow()
        super.onDestroy()
    }
}
