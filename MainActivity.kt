package com.example.cctv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.*
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
import java.net.URL
import java.util.concurrent.Executors

object FrameStore {
    @Volatile var jpeg: ByteArray? = null
    @Volatile var seq = 0L
    @Volatile var lastMotion = 0L
    val lock = Object()
    fun put(b: ByteArray) { synchronized(lock) { jpeg = b; seq++; lock.notifyAll() } }
}

class MjpegStream : InputStream() {
    private var buf = ByteArray(0)
    private var pos = 0
    private var lastSeq = -1L
    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xff
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= buf.size) {
            var frame: ByteArray?
            synchronized(FrameStore.lock) {
                while (FrameStore.seq == lastSeq) FrameStore.lock.wait(2000)
                lastSeq = FrameStore.seq
                frame = FrameStore.jpeg
            }
            val f = frame ?: return 0
            val head = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray()
            buf = head + f + "\r\n".toByteArray()
            pos = 0
        }
        val n = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }
}

class CamServer(port: Int, private val password: String) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val pw = session.parameters["pw"]?.firstOrNull() ?: ""
        if (pw != password) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Wrong password")
        return when (session.uri) {
            "/stream" -> newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", MjpegStream())
            "/status" -> {
                val m = System.currentTimeMillis() - FrameStore.lastMotion < 3000 && FrameStore.lastMotion > 0
                newFixedLengthResponse(Response.Status.OK, "text/plain", if (m) "MOTION" else "OK")
            }
            else -> newFixedLengthResponse("Phone CCTV")
        }
    }
}

class MainActivity : ComponentActivity() {
    private var server: CamServer? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var viewerRunning = false
    private var pendingAction: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pendingAction?.invoke() else toast("Camera permission chahiye")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHome()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun col(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(40), dp(20), dp(20))
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun showHome() {
        viewerRunning = false
        val c = col()
        c.addView(TextView(this).apply { text = "Phone CCTV"; textSize = 28f; gravity = Gravity.CENTER })
        c.addView(Button(this).apply { text = "Camera Mode"; setOnClickListener { showCameraSetup() } })
        c.addView(Button(this).apply { text = "Viewer Mode"; setOnClickListener { showViewerSetup() } })
        setContentView(c)
    }

    private fun showCameraSetup() {
        val c = col()
        c.addView(TextView(this).apply { text = "Camera Mode"; textSize = 22f })
        val pw = EditText(this).apply { hint = "Password set karein"; setText("1234") }
        c.addView(pw)
        c.addView(Button(this).apply {
            text = "Start Camera"
            setOnClickListener {
                val p = pw.text.toString().trim()
                if (p.isEmpty()) { toast("Password likhein"); return@setOnClickListener }
                withCamera { startCamera(p) }
            }
        })
        c.addView(Button(this).apply { text = "Back"; setOnClickListener { showHome() } })
        setContentView(c)
    }

    private fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingAction = action; permLauncher.launch(Manifest.permission.CAMERA) }
    }

    private fun localIps(): String {
        val l = mutableListOf<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) for (a in ni.inetAddresses)
                if (a is Inet4Address && !a.isLoopbackAddress) l.add(a.hostAddress ?: "")
        } catch (_: Exception) {}
        return if (l.isEmpty()) "IP nahi mila (Wi-Fi/hotspot chalu karein)" else l.joinToString("\n")
    }

    private fun startCamera(password: String) {
        server?.stop()
        server = CamServer(8080, password).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val c = col()
        c.addView(TextView(this).apply {
            text = "Camera chalu hai\n\nViewer phone mein ye IP daalein:\n${localIps()}\n\nPort: 8080"
            textSize = 18f
        })
        val status = TextView(this).apply { textSize = 16f }
        c.addView(status)
        c.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                ProcessCameraProvider.getInstance(this@MainActivity).get().unbindAll()
                server?.stop(); server = null; showHome()
            }
        })
        setContentView(c)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            var lastTime = 0L
            var prev: IntArray? = null
            analysis.setAnalyzer(exec) { img ->
                val now = System.currentTimeMillis()
                if (now - lastTime < 120) { img.close(); return@setAnalyzer }
                lastTime = now
                try {
                    val nv21 = toNv21(img)
                    val w = img.width; val h = img.height
                    // motion: sample luminance grid
                    val samples = IntArray((w / 8) * (h / 8))
                    var i = 0
                    for (y in 0 until h / 8) for (x in 0 until w / 8) samples[i++] = nv21[(y * 8) * w + x * 8].toInt() and 0xff
                    prev?.let { p ->
                        var diff = 0
                        for (k in samples.indices) if (Math.abs(samples[k] - p[k]) > 30) diff++
                        if (diff > samples.size * 0.02) FrameStore.lastMotion = now
                    }
                    prev = samples
                    val out = ByteArrayOutputStream()
                    YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 70, out)
                    var bytes = out.toByteArray()
                    val rot = img.imageInfo.rotationDegrees
                    if (rot != 0) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val m = Matrix().apply { postRotate(rot.toFloat()) }
                        val rb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                        val o2 = ByteArrayOutputStream()
                        rb.compress(Bitmap.CompressFormat.JPEG, 70, o2)
                        bytes = o2.toByteArray()
                    }
                    FrameStore.put(bytes)
                } finally { img.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toNv21(img: ImageProxy): ByteArray {
        val w = img.width; val h = img.height
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val out = ByteArray(w * h * 3 / 2)
        var pos = 0
        for (r in 0 until h) for (c in 0 until w) out[pos++] = y.buffer.get(r * y.rowStride + c * y.pixelStride)
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            out[pos++] = v.buffer.get(r * v.rowStride + c * v.pixelStride)
            out[pos++] = u.buffer.get(r * u.rowStride + c * u.pixelStride)
        }
        return out
    }

    private fun showViewerSetup() {
        val c = col()
        c.addView(TextView(this).apply { text = "Viewer Mode"; textSize = 22f })
        val ip = EditText(this).apply { hint = "Camera phone ka IP (jaise 192.168.43.1)" }
        val pw = EditText(this).apply { hint = "Password"; setText("1234") }
        c.addView(ip); c.addView(pw)
        c.addView(Button(this).apply {
            text = "Connect"
            setOnClickListener {
                val a = ip.text.toString().trim(); val p = pw.text.toString().trim()
                if (a.isEmpty()) toast("IP likhein") else showViewer(a, p)
            }
        })
        c.addView(Button(this).apply { text = "Back"; setOnClickListener { showHome() } })
        setContentView(c)
    }

    private fun showViewer(ip: String, pw: String) {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val banner = TextView(this).apply { text = "Connecting..."; textSize = 18f; setPadding(dp(12), dp(36), dp(12), dp(12)) }
        val web = WebView(this).apply {
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(Color.BLACK)
            loadUrl("http://$ip:8080/stream?pw=$pw")
        }
        val back = Button(this).apply { text = "Disconnect"; setOnClickListener { viewerRunning = false; showHome() } }
        c.addView(banner); c.addView(web, LinearLayout.LayoutParams(-1, 0, 1f)); c.addView(back)
        setContentView(c)

        viewerRunning = true
        Thread {
            var wasMotion = false
            while (viewerRunning) {
                try {
                    val t = URL("http://$ip:8080/status?pw=$pw").openConnection().apply { connectTimeout = 2500; readTimeout = 2500 }
                        .getInputStream().bufferedReader().readText()
                    val motion = t.trim() == "MOTION"
                    runOnUiThread {
                        banner.text = if (motion) "⚠ MOTION DETECTED" else "Connected, sab shaant"
                        banner.setBackgroundColor(if (motion) Color.RED else Color.TRANSPARENT)
                    }
                    if (motion && !wasMotion) alertUser()
                    wasMotion = motion
                } catch (e: Exception) {
                    runOnUiThread { banner.text = "Connection nahi ho raha (IP/password check karein)" }
                }
                Thread.sleep(2000)
            }
        }.start()
    }

    private fun alertUser() {
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    override fun onDestroy() { viewerRunning = false; server?.stop(); super.onDestroy() }
}
