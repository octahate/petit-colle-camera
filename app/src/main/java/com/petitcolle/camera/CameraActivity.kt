package com.petitcolle.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.util.Base64
import android.util.Size
import android.view.ViewConfiguration
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {
    private lateinit var camera: PetitColleCameraView
    private lateinit var printer: FicheroPrinter
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val settingsStore by lazy { getSharedPreferences("dither_profiles", Context.MODE_PRIVATE) }
    private val settingsByDither by lazy {
        DitherMode.entries.associateWith { loadSettings(it) }.toMutableMap()
    }
    @Volatile private var settings = RenderSettings()
    @Volatile private var currentFrame: ThermalFrame? = null
    @Volatile private var frozen = false
    @Volatile private var captureRequest: CaptureRequest? = null
    private var pendingLegacyCapture: CaptureRequest? = null
    private var adjustment = Adjustment.EXPOSURE
    private var printerTelemetry = PrinterTelemetry()

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else camera.setStatus("CAMERA ACCESS NEEDED")
    }

    private val requestBluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBluetoothPermissions()) printer.scanAndConnect()
        else {
            camera.setLinkState(LinkState.ERROR)
            camera.setStatus("BLUETOOTH ACCESS NEEDED")
        }
    }

    private val requestLegacyStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingLegacyCapture
        pendingLegacyCapture = null
        if (granted && pending != null) beginCapture(pending)
        else camera.setStatus("PHOTO ACCESS NEEDED")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installSharpBaseline = !settingsStore.getBoolean("sharp_atkinson_baseline_applied", false)
        val savedMode = if (installSharpBaseline) DitherMode.ATKINSON.name
            else settingsStore.getString("active_dither", DitherMode.ATKINSON.name)
        val activeMode = runCatching { DitherMode.valueOf(savedMode ?: DitherMode.ATKINSON.name) }.getOrDefault(DitherMode.ATKINSON)
        if (installSharpBaseline) {
            settingsStore.edit {
                putBoolean("sharp_atkinson_baseline_applied", true)
                putString("active_dither", DitherMode.ATKINSON.name)
            }
        }
        settings = settingsByDither.getValue(activeMode).copy(auto = true, toneCurve = null)
        ThermalRenderer.resetAuto(activeMode)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        cameraExecutor = Executors.newSingleThreadExecutor()
        camera = PetitColleCameraView(this) { handle(it) }
        printer = FicheroPrinter(
            context = this,
            onStatus = { message -> runOnUiThread { handlePrinterStatus(message) } },
            onTelemetry = { telemetry -> runOnUiThread { handlePrinterTelemetry(telemetry) } },
        )
        setContentView(camera)
        updateControlReadout()
        requestNeededPermissions()
    }

    private fun handlePrinterStatus(message: String) {
        val upper = message.uppercase(Locale.US)
        when {
            upper.contains("LOOKING FOR") || upper.contains("CONNECTING TO") || upper.contains("CHECKING PRINTER") -> {
                camera.setLinkState(LinkState.CONNECTING)
                camera.setStatus("CONNECTING...")
            }
            upper.contains("FICHERO READY") -> {
                camera.setLinkState(LinkState.READY)
                camera.setStatus("PRINTER READY")
            }
            upper.contains("PRINTING THE FRAME") -> {
                camera.setLinkState(LinkState.PRINTING)
                camera.setStatus("PRINTING...")
            }
            upper.startsWith("SENT") -> {
                camera.setLinkState(LinkState.READY)
                camera.setStatus("PRINT SENT")
            }
            upper.contains("CONNECT THE FICHERO FIRST") -> {
                camera.setLinkState(LinkState.OFF)
                camera.setStatus("NO PRINTER")
            }
            upper.contains("NO FICHERO FOUND") -> {
                camera.setLinkState(LinkState.ERROR)
                camera.setStatus("NO PRINTER")
            }
            upper.contains("DISCONNECTED") || upper.contains("INTERRUPTED") ||
                upper.contains("FAILED") || upper.contains("UNAVAILABLE") ||
                upper.contains("NOT A SUPPORTED") -> {
                camera.setLinkState(LinkState.ERROR)
                camera.setStatus("PRINTER ERROR")
            }
            else -> camera.setStatus(upper.take(18))
        }
    }

    private fun handlePrinterTelemetry(value: PrinterTelemetry) {
        val previous = printerTelemetry
        printerTelemetry = value
        camera.setPrinterTelemetry(value)
        when {
            value.coverOpen && !previous.coverOpen -> {
                camera.setLinkState(LinkState.ERROR)
                camera.setStatus("COVER OPEN")
            }
            value.outOfPaper && !previous.outOfPaper -> {
                camera.setLinkState(LinkState.ERROR)
                camera.setStatus("NO PAPER")
            }
            value.overheated && !previous.overheated -> {
                camera.setLinkState(LinkState.ERROR)
                camera.setStatus("PRINTER HOT")
            }
            value.lowBattery && !previous.lowBattery -> {
                camera.setLinkState(LinkState.ERROR)
                camera.setStatus("LOW BATTERY")
            }
            value.printing -> camera.setLinkState(LinkState.PRINTING)
            previous.hasPrinterFault() && !value.hasPrinterFault() -> {
                camera.setLinkState(LinkState.READY)
                camera.setStatus("PRINTER READY")
            }
            previous.printing && !value.printing -> camera.setLinkState(LinkState.READY)
        }
    }

    private fun PrinterTelemetry.hasPrinterFault(): Boolean =
        coverOpen || outOfPaper || lowBattery || overheated

    private fun handle(action: CameraAction) {
        when (action) {
            CameraAction.SHUTTER -> toggleFreeze()
            CameraAction.PRINT -> printFrame()
            CameraAction.CONNECT -> connectPrinter()
            CameraAction.SWITCH_CAMERA -> switchCamera()
            CameraAction.HELP -> camera.toggleHelp()
            CameraAction.SELECT -> if (settings.auto) adoptAutoValues() else {
                adjustment = adjustment.next()
                updateControlReadout()
                camera.setStatus(adjustmentReadout())
            }
            CameraAction.DITHER -> selectNextDither()
            CameraAction.AUTO_CURRENT -> enableAuto()
            CameraAction.INCREASE -> changeAdjustment(1)
            CameraAction.DECREASE -> changeAdjustment(-1)
            CameraAction.RESET -> resetAdjustment()
            CameraAction.RESET_ALL -> resetCurrentDither()
        }
    }

    private fun loadSettings(mode: DitherMode): RenderSettings {
        val key = mode.name
        return RenderSettings(
            exposure = settingsStore.getFloat("$key.exposure", 0f),
            contrast = settingsStore.getFloat("$key.contrast", 1f),
            threshold = settingsStore.getFloat("$key.threshold", .5f),
            zoom = settingsStore.getFloat("$key.zoom", 1f),
            errorDiffusion = settingsStore.getFloat("$key.error", 1f),
            toneCurve = settingsStore.getString("$key.curve", null)?.let {
                runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()?.takeIf { bytes -> bytes.size == 256 }
            },
            auto = settingsStore.getBoolean("$key.auto", true),
            dither = mode,
        )
    }

    private fun rememberSettings() {
        settingsByDither[settings.dither] = settings
        val key = settings.dither.name
        settingsStore.edit {
            putString("active_dither", key)
            putFloat("$key.exposure", settings.exposure)
            putFloat("$key.contrast", settings.contrast)
            putFloat("$key.threshold", settings.threshold)
            putFloat("$key.zoom", settings.zoom)
            putFloat("$key.error", settings.errorDiffusion)
            val curve = settings.toneCurve
            if (curve == null) remove("$key.curve") else putString("$key.curve", Base64.encodeToString(curve, Base64.NO_WRAP))
            putBoolean("$key.auto", settings.auto)
        }
    }

    private fun selectNextDither() {
        rememberSettings()
        val next = settings.dither.next()
        settings = settingsByDither.getValue(next).copy(auto = true, toneCurve = null)
        adjustment = Adjustment.EXPOSURE
        ThermalRenderer.resetAuto(next)
        rememberSettings()
        updateControlReadout()
        restoreCameraStatus()
    }

    private fun enableAuto() {
        settings = settings.copy(auto = true, toneCurve = null)
        adjustment = Adjustment.EXPOSURE
        ThermalRenderer.resetAuto(settings.dither)
        rememberSettings()
        updateControlReadout()
        camera.setStatus("AUTO")
    }

    private fun adoptAutoValues() {
        val calibration = currentFrame?.calibration ?: run {
            camera.setStatus("AUTO STILL MEASURING")
            return
        }
        settings = settings.copy(
            auto = false,
            exposure = 0f,
            contrast = 1f,
            threshold = calibration.threshold,
            toneCurve = calibration.toneCurve,
        )
        adjustment = Adjustment.EXPOSURE
        rememberSettings()
        updateControlReadout()
        camera.setStatus("AUTO VALUES READY")
    }

    private fun restoreCameraStatus() {
        camera.clearTemporaryStatus()
    }

    private fun resetCurrentDither() {
        settings = RenderSettings(dither = settings.dither)
        rememberSettings()
        updateControlReadout()
        camera.setStatus("${settings.dither.statusLabel()} RESET")
    }

    private fun toggleFreeze() {
        if (frozen) {
            frozen = false
            camera.clearTemporaryStatus()
            return
        }
        if (currentFrame == null) {
            camera.setStatus("WAITING FOR LIVE IMAGE")
            return
        }
        beginCapture(CaptureRequest.SAVE)
    }

    private fun printFrame() {
        val frame = currentFrame ?: run {
            camera.setStatus("WAITING FOR LIVE IMAGE")
            return
        }
        if (frozen) {
            printer.print(frame)
        } else {
            beginCapture(CaptureRequest.SAVE_AND_PRINT)
        }
    }

    private fun beginCapture(request: CaptureRequest) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingLegacyCapture = request
            requestLegacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        captureRequest = request
        camera.setStatus("CAPTURING...")
    }

    private fun savePhoto(photo: RenderedPhoto) {
        try {
            val bitmap = createBitmap(photo.width, photo.height)
            val pixels = IntArray(photo.dots.size) { index -> if (photo.dots[index].toInt() == 1) Color.BLACK else Color.WHITE }
            bitmap.setPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
            val name = "PetitColle_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PetitColle")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create gallery image")
            contentResolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            } ?: error("Could not open gallery image")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            bitmap.recycle()
            runOnUiThread { camera.setStatus("SAVED") }
        } catch (_: Exception) {
            runOnUiThread { camera.setStatus("SAVE ERROR") }
        }
    }

    private fun changeAdjustment(direction: Int) {
        if (settings.auto && adjustment in setOf(Adjustment.EXPOSURE, Adjustment.CONTRAST, Adjustment.THRESHOLD)) {
            camera.setStatus("AUTO CONTROLS ${adjustment.label}")
            return
        }
        settings = when (adjustment) {
            Adjustment.EXPOSURE -> settings.copy(exposure = (settings.exposure + direction * .08f).coerceIn(-1.5f, 1.5f))
            Adjustment.CONTRAST -> settings.copy(contrast = (settings.contrast + direction * .1f).coerceIn(.25f, 4f))
            Adjustment.THRESHOLD -> settings.copy(threshold = (settings.threshold + direction * .04f).coerceIn(.15f, .85f))
            Adjustment.ERROR -> settings.copy(errorDiffusion = (settings.errorDiffusion + direction * .1f).coerceIn(0f, 1.5f))
            Adjustment.ZOOM -> settings.copy(zoom = (settings.zoom + direction * .25f).coerceIn(1f, 2f))
        }
        rememberSettings()
        updateControlReadout()
        camera.setStatus(adjustmentReadout())
    }

    private fun resetAdjustment() {
        if (settings.auto && adjustment in setOf(Adjustment.EXPOSURE, Adjustment.CONTRAST, Adjustment.THRESHOLD)) {
            camera.setStatus("AUTO CONTROLS ${adjustment.label}")
            return
        }
        settings = when (adjustment) {
            Adjustment.EXPOSURE -> settings.copy(exposure = 0f)
            Adjustment.CONTRAST -> settings.copy(contrast = 1f)
            Adjustment.THRESHOLD -> settings.copy(threshold = .5f)
            Adjustment.ERROR -> settings.copy(errorDiffusion = 1f)
            Adjustment.ZOOM -> settings.copy(zoom = 1f)
        }
        rememberSettings()
        updateControlReadout()
        camera.setStatus(adjustmentReadout() + " RESET")
    }

    private fun adjustmentReadout(): String {
        val value = when (adjustment) {
            Adjustment.EXPOSURE -> "%+.2f".format(Locale.US, settings.exposure)
            Adjustment.CONTRAST -> "%.1f".format(Locale.US, settings.contrast)
            Adjustment.THRESHOLD -> "%d".format(Locale.US, (settings.threshold * 100).toInt())
            Adjustment.ERROR -> "%d%%".format(Locale.US, (settings.errorDiffusion * 100).toInt())
            Adjustment.ZOOM -> "%.2fX".format(Locale.US, settings.zoom)
        }
        return "${adjustment.label} $value"
    }

    private fun updateControlReadout() {
        camera.setStatusFields(if (settings.auto) "AUTO" else "MANUAL", settings.dither.statusLabel())
    }

    private fun requestNeededPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else
            requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun connectPrinter() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermissions()) {
            requestBluetoothPermissions.launch(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            )
            return
        }
        printer.scanAndConnect()
    }

    private fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera(cameraProvider ?: return@addListener)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        val next = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(next).build()
        if (!provider.hasCamera(selector)) {
            camera.setStatus("CAMERA NOT AVAILABLE")
            return
        }
        lensFacing = next
        frozen = false
        captureRequest = null
        if (settings.auto) ThermalRenderer.resetAuto(settings.dither)
        bindCamera(provider)
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val selectedLens = lensFacing
        val selector = CameraSelector.Builder().requireLensFacing(selectedLens).build()
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(cameraExecutor) { image ->
            try {
                val activeSettings = settings
                val mirrored = selectedLens == CameraSelector.LENS_FACING_FRONT
                val frame = ThermalRenderer.process(image, activeSettings, mirrored)
                val requestedCapture = captureRequest
                if (requestedCapture != null && !frozen) {
                    captureRequest = null
                    val photo = ThermalRenderer.processHighResolution(
                        image,
                        activeSettings,
                        mirrored,
                        frame.calibration,
                    )
                    frozen = true
                    currentFrame = frame
                    camera.post {
                        camera.show(frame)
                        camera.setStatus("SAVING...")
                        if (requestedCapture == CaptureRequest.SAVE_AND_PRINT) printer.print(frame)
                    }
                    cameraExecutor.execute { savePhoto(photo) }
                } else if (!frozen) {
                    currentFrame = frame
                    camera.post { if (!frozen) camera.show(frame) }
                }
            } finally { image.close() }
        }
        provider.unbindAll()
        provider.bindToLifecycle(this, selector, analysis)
        camera.setStatus(if (selectedLens == CameraSelector.LENS_FACING_FRONT) "FRONT CAMERA" else "REAR CAMERA")
    }

    override fun onDestroy() { super.onDestroy(); printer.close(); cameraExecutor.shutdown() }
}

private enum class Adjustment(val label: String) {
    EXPOSURE("LIGHT"), CONTRAST("CONTRAST"), THRESHOLD("CUT"), ERROR("ERROR"), ZOOM("ZOOM");
    fun next() = entries[(ordinal + 1) % entries.size]
}

private fun DitherMode.statusLabel() = when (this) {
    DitherMode.ATKINSON -> "ATKINSON"
    DitherMode.BAYER4 -> "DOT"
    DitherMode.CLUSTER -> "CLUSTER"
    DitherMode.HALFTONE -> "HALFTONE"
    DitherMode.THRESHOLD -> "THRESHOLD"
    else -> label
}

private enum class CaptureRequest { SAVE, SAVE_AND_PRINT }

private enum class LinkState { OFF, CONNECTING, READY, PRINTING, ERROR }

private enum class CameraAction { SHUTTER, PRINT, CONNECT, SWITCH_CAMERA, HELP, SELECT, DITHER, AUTO_CURRENT, INCREASE, DECREASE, RESET, RESET_ALL }

/** All artwork, the live raster and hit targets use the same source-pixel coordinate system. */
@SuppressLint("ViewConstructor")
private class PetitColleCameraView(context: Context, private val onAction: (CameraAction) -> Unit) : android.view.View(context) {
    private val faceplate = BitmapFactory.decodeResource(resources, R.drawable.zr1_layout_b,
        BitmapFactory.Options().apply { inScaled = false })
    private val bitmap = createBitmap(PRINT_WIDTH, PRINT_HEIGHT)
    private val pixels = IntArray(PRINT_WIDTH * PRINT_HEIGHT)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val pixelPaint = Paint().apply { isFilterBitmap = false }
    private val helpPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(166, 178, 155) }
    private val helpBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(53, 58, 52)
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val helpTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(29, 39, 32)
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }
    private val helpLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(29, 39, 32)
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        letterSpacing = .035f
    }
    private val helpRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(85, 37, 48, 40)
        strokeWidth = 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(43, 46, 41)
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        letterSpacing = .04f
    }
    private val statusShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(72, 25, 27, 24) }
    private val statusBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(55, 59, 53) }
    private val statusInsetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(116, 123, 109) }
    private val statusScreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(166, 178, 155) }
    private val statusGlarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 235, 241, 226)
        style = Paint.Style.STROKE
        strokeWidth = 1.25f
    }
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 39, 32)
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        textSize = 23f
        textScaleX = .88f
        letterSpacing = .075f
    }
    private val statusDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 39, 50, 42)
        strokeWidth = 1.25f
    }
    private val statusIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 39, 32)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val statusIconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 39, 32)
        style = Paint.Style.FILL
    }
    private val ledBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(48, 49, 43) }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ledGlintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 255, 240) }
    private val artwork = RectF(100f, 20f, 786f, 1758f)
    // 364 / 728 == 96 / 192. The image is neither cropped nor stretched.
    private val lcd = RectF(208f, 447f, 572f, 1175f)
    private val statusBezel = RectF(250f, 1676f, 636f, 1734f)
    private val statusScreen = RectF(257f, 1683f, 629f, 1727f)
    private val helpBezel = RectF(132f, 306f, 754f, 1464f)
    private val helpBox = RectF(146f, 320f, 740f, 1450f)
    private val statusShadow = RectF(statusBezel).apply { offset(0f, 2f) }
    private val statusInset = RectF(statusScreen).apply { inset(-2.5f, -2.5f) }
    private val helpShadow = RectF(helpBezel).apply { offset(0f, 5f) }
    private val helpInset = RectF(helpBox).apply { inset(-5f, -5f) }
    private val edgeFade = 42f
    private val edgeFill = Color.rgb(235, 233, 220)
    private val edgeClear = Color.argb(0, 235, 233, 220)
    private val leftEdgePaint = Paint().apply {
        shader = LinearGradient(artwork.left, 0f, artwork.left + edgeFade, 0f, edgeFill, edgeClear, Shader.TileMode.CLAMP)
    }
    private val rightEdgePaint = Paint().apply {
        shader = LinearGradient(artwork.right, 0f, artwork.right - edgeFade, 0f, edgeFill, edgeClear, Shader.TileMode.CLAMP)
    }
    private val topEdgePaint = Paint().apply {
        shader = LinearGradient(0f, artwork.top, 0f, artwork.top + edgeFade, edgeFill, edgeClear, Shader.TileMode.CLAMP)
    }
    private val bottomEdgePaint = Paint().apply {
        shader = LinearGradient(0f, artwork.bottom, 0f, artwork.bottom - edgeFade, edgeFill, edgeClear, Shader.TileMode.CLAMP)
    }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var factor = 1f
    private var dx = 0f
    private var dy = 0f
    private var operationMode = "AUTO"
    private var renderStyle = "ATKINSON"
    private var temporaryStatus: String? = null
    private var linkState = LinkState.OFF
    private var printerTelemetry = PrinterTelemetry()
    private val clearTemporary = Runnable { temporaryStatus = null; invalidate() }
    private var showingHelp = false
    private var pressed: Target? = null
    private data class Target(val bounds: RectF, val action: CameraAction? = null)
    private val dismissHelpTarget = Target(artwork, CameraAction.HELP)
    private val targets = listOf(
        Target(lcd, CameraAction.SWITCH_CAMERA),
        Target(RectF(250f, 95f, 590f, 245f), CameraAction.HELP),
        Target(RectF(610f, 287f, 765f, 500f), CameraAction.PRINT),
        Target(RectF(240f, 1240f, 515f, 1520f), CameraAction.SHUTTER),
        Target(RectF(669f, 800f, 775f, 987f), CameraAction.INCREASE),
        Target(RectF(660f, 1000f, 775f, 1202f), CameraAction.DECREASE),
        Target(RectF(610f, 1270f, 719f, 1390f), CameraAction.DITHER),
        Target(RectF(529f, 1460f, 639f, 1575f), CameraAction.SELECT),
        Target(RectF(404f, 1545f, 520f, 1660f), CameraAction.CONNECT),
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        factor = minOf(w / artwork.width(), h / artwork.height())
        dx = (w - artwork.width() * factor) / 2f - artwork.left * factor
        dy = (h - artwork.height() * factor) / 2f - artwork.top * factor
    }

    fun show(frame: ThermalFrame) {
        frame.dots.indices.forEach { i ->
            pixels[i] = if (frame.dots[i].toInt() == 1) Color.rgb(24, 34, 28) else Color.rgb(157, 177, 153)
        }
        bitmap.setPixels(pixels, 0, PRINT_WIDTH, 0, 0, PRINT_WIDTH, PRINT_HEIGHT)
        invalidate()
    }
    fun setStatusFields(mode: String, style: String) {
        operationMode = mode.uppercase(Locale.US)
        renderStyle = style.uppercase(Locale.US)
        invalidate()
    }

    fun setStatus(value: String) {
        uiHandler.removeCallbacks(clearTemporary)
        temporaryStatus = value.uppercase(Locale.US)
        val duration = when {
            value.contains("PRINTING", ignoreCase = true) -> 4_000L
            value.contains("ERROR", ignoreCase = true) || value.contains("NO PRINTER", ignoreCase = true) -> 2_800L
            else -> 1_800L
        }
        uiHandler.postDelayed(clearTemporary, duration)
        invalidate()
    }

    fun clearTemporaryStatus() {
        uiHandler.removeCallbacks(clearTemporary)
        temporaryStatus = null
        invalidate()
    }

    fun setLinkState(value: LinkState) {
        linkState = value
        invalidate()
    }

    fun setPrinterTelemetry(value: PrinterTelemetry) {
        printerTelemetry = value
        invalidate()
    }

    fun toggleHelp() { showingHelp = !showingHelp; invalidate() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(235, 233, 220))
        canvas.withSave {
            translate(dx, dy)
            scale(factor, factor)
            clipRect(artwork)
            drawBitmap(faceplate, 0f, 0f, artPaint)
            drawBitmap(bitmap, null, lcd, pixelPaint)
            drawEdgeBlends(this)
            labelPaint.textSize = 16f
            drawText("DTH", 724f, 1338f, labelPaint)
            drawText("MODE", 648f, 1519f, labelPaint)
            drawText("LINK", 542f, 1608f, labelPaint)
            drawLinkLed(this)
            drawSecondaryLcd(this)
            if (showingHelp) drawHelp(this)
        }
    }

    private fun drawSecondaryLcd(canvas: Canvas) {
        canvas.drawRoundRect(statusShadow, 7f, 7f, statusShadowPaint)
        canvas.drawRoundRect(statusBezel, 7f, 7f, statusBezelPaint)
        canvas.drawRoundRect(statusInset, 4f, 4f, statusInsetPaint)
        canvas.drawRoundRect(statusScreen, 2.5f, 2.5f, statusScreenPaint)
        canvas.drawRoundRect(statusScreen, 2.5f, 2.5f, statusGlarePaint)

        val message = temporaryStatus
        if (message != null) {
            val cell = fitLcdCell(message, statusScreen.width() - 20f, 3f)
            drawLcdText(canvas, message, statusScreen.centerX(), statusScreen.centerY(), cell, Paint.Align.CENTER)
        } else {
            val cell = 3f
            drawLcdText(canvas, operationMode, statusScreen.left + 10f, statusScreen.centerY(), cell, Paint.Align.LEFT)
            drawLcdText(canvas, renderStyle, statusScreen.right - 10f, statusScreen.centerY(), cell, Paint.Align.RIGHT)
            val leftDivider = statusScreen.left + 117f
            val rightDivider = statusScreen.right - 153f
            canvas.drawLine(leftDivider, statusScreen.top + 9f, leftDivider, statusScreen.bottom - 9f, statusDividerPaint)
            canvas.drawLine(rightDivider, statusScreen.top + 9f, rightDivider, statusScreen.bottom - 9f, statusDividerPaint)
            drawPrinterTelemetry(canvas, (leftDivider + rightDivider) * .5f, statusScreen.centerY())
        }
    }

    private fun drawPrinterTelemetry(canvas: Canvas, centerX: Float, centerY: Float) {
        val telemetry = printerTelemetry
        if (telemetry.batteryPercent == null && !telemetry.coverOpen && !telemetry.outOfPaper &&
            !telemetry.lowBattery && !telemetry.charging && !telemetry.overheated
        ) return

        telemetry.batteryPercent?.let { percent ->
            drawBatteryIcon(canvas, centerX - 21f, centerY, percent, telemetry.charging)
        }
        val alertX = centerX + 30f
        when {
            telemetry.coverOpen -> drawCoverOpenIcon(canvas, alertX, centerY)
            telemetry.outOfPaper -> drawPaperOutIcon(canvas, alertX, centerY)
            telemetry.overheated -> drawHeatIcon(canvas, alertX, centerY)
            telemetry.lowBattery -> drawAlertMark(canvas, alertX, centerY)
        }
    }

    private fun drawBatteryIcon(canvas: Canvas, centerX: Float, centerY: Float, percent: Int, charging: Boolean) {
        val body = RectF(centerX - 15f, centerY - 8f, centerX + 12f, centerY + 8f)
        canvas.drawRoundRect(body, 2f, 2f, statusIconPaint)
        canvas.drawRect(centerX + 13f, centerY - 4f, centerX + 16f, centerY + 4f, statusIconFillPaint)
        val segments = when {
            percent >= 88 -> 4
            percent >= 63 -> 3
            percent >= 38 -> 2
            percent >= 13 -> 1
            else -> 0
        }
        repeat(segments) { index ->
            val left = body.left + 3f + index * 5.7f
            canvas.drawRect(left, body.top + 3f, left + 4f, body.bottom - 3f, statusIconFillPaint)
        }
        if (charging) {
            val path = android.graphics.Path().apply {
                moveTo(centerX + 1f, centerY - 7f)
                lineTo(centerX - 4f, centerY + 1f)
                lineTo(centerX + 1f, centerY + 1f)
                lineTo(centerX - 2f, centerY + 8f)
                lineTo(centerX + 6f, centerY - 2f)
                lineTo(centerX + 1f, centerY - 2f)
                close()
            }
            statusIconFillPaint.color = statusScreenPaint.color
            canvas.drawPath(path, statusIconFillPaint)
            statusIconFillPaint.color = Color.rgb(28, 39, 32)
        }
    }

    private fun drawCoverOpenIcon(canvas: Canvas, centerX: Float, centerY: Float) {
        canvas.drawRect(centerX - 9f, centerY, centerX + 9f, centerY + 7f, statusIconPaint)
        canvas.drawLine(centerX - 9f, centerY - 2f, centerX + 4f, centerY - 10f, statusIconPaint)
        canvas.drawLine(centerX + 4f, centerY - 10f, centerX + 10f, centerY - 5f, statusIconPaint)
    }

    private fun drawPaperOutIcon(canvas: Canvas, centerX: Float, centerY: Float) {
        val paper = android.graphics.Path().apply {
            moveTo(centerX - 8f, centerY - 10f)
            lineTo(centerX + 8f, centerY - 10f)
            lineTo(centerX + 8f, centerY + 9f)
            lineTo(centerX + 4f, centerY + 6f)
            lineTo(centerX, centerY + 9f)
            lineTo(centerX - 4f, centerY + 6f)
            lineTo(centerX - 8f, centerY + 9f)
            close()
        }
        canvas.drawPath(paper, statusIconPaint)
        drawAlertMark(canvas, centerX, centerY)
    }

    private fun drawHeatIcon(canvas: Canvas, centerX: Float, centerY: Float) {
        for (offset in floatArrayOf(-6f, 0f, 6f)) {
            val path = android.graphics.Path().apply {
                moveTo(centerX + offset, centerY + 9f)
                cubicTo(centerX + offset - 4f, centerY + 4f, centerX + offset + 4f, centerY, centerX + offset, centerY - 5f)
                cubicTo(centerX + offset - 3f, centerY - 8f, centerX + offset + 2f, centerY - 10f, centerX + offset, centerY - 12f)
            }
            canvas.drawPath(path, statusIconPaint)
        }
    }

    private fun drawAlertMark(canvas: Canvas, centerX: Float, centerY: Float) {
        canvas.drawLine(centerX, centerY - 7f, centerX, centerY + 2f, statusIconPaint)
        canvas.drawCircle(centerX, centerY + 7f, 1.5f, statusIconFillPaint)
    }

    private fun fitLcdCell(value: String, maximumWidth: Float, preferred: Float): Float {
        var cell = preferred
        while (lcdTextWidth(value, cell) > maximumWidth && cell > 1.65f) cell -= .1f
        return cell
    }

    private fun lcdTextWidth(value: String, cell: Float): Float =
        if (value.isEmpty()) 0f else value.length * cell * 6f - cell

    private fun drawLcdText(
        canvas: Canvas,
        value: String,
        anchorX: Float,
        centerY: Float,
        cell: Float,
        alignment: Paint.Align,
    ) {
        val normalized = value.uppercase(Locale.US).replace('×', 'X').replace(',', '.')
        val width = lcdTextWidth(normalized, cell)
        val startX = when (alignment) {
            Paint.Align.CENTER -> anchorX - width * .5f
            Paint.Align.RIGHT -> anchorX - width
            else -> anchorX
        }
        val startY = centerY - cell * 3.5f
        val dot = cell * .78f
        normalized.forEachIndexed { characterIndex, character ->
            val rows = lcdGlyph(character)
            for (row in 0 until 7) for (column in 0 until 5) {
                if (rows[row] and (1 shl (4 - column)) != 0) {
                    val left = startX + characterIndex * cell * 6f + column * cell
                    val top = startY + row * cell
                    canvas.drawRect(left, top, left + dot, top + dot, statusTextPaint)
                }
            }
        }
    }

    private val lcdGlyphCache = mutableMapOf<Char, IntArray>()

    private fun lcdGlyph(character: Char): IntArray = lcdGlyphCache.getOrPut(character) { when (character) {
        'A' -> intArrayOf(14,17,17,31,17,17,17)
        'B' -> intArrayOf(30,17,17,30,17,17,30)
        'C' -> intArrayOf(15,16,16,16,16,16,15)
        'D' -> intArrayOf(30,17,17,17,17,17,30)
        'E' -> intArrayOf(31,16,16,30,16,16,31)
        'F' -> intArrayOf(31,16,16,30,16,16,16)
        'G' -> intArrayOf(15,16,16,23,17,17,15)
        'H' -> intArrayOf(17,17,17,31,17,17,17)
        'I' -> intArrayOf(31,4,4,4,4,4,31)
        'J' -> intArrayOf(7,2,2,2,18,18,12)
        'K' -> intArrayOf(17,18,20,24,20,18,17)
        'L' -> intArrayOf(16,16,16,16,16,16,31)
        'M' -> intArrayOf(17,27,21,21,17,17,17)
        'N' -> intArrayOf(17,25,21,19,17,17,17)
        'O' -> intArrayOf(14,17,17,17,17,17,14)
        'P' -> intArrayOf(30,17,17,30,16,16,16)
        'Q' -> intArrayOf(14,17,17,17,21,18,13)
        'R' -> intArrayOf(30,17,17,30,20,18,17)
        'S' -> intArrayOf(15,16,16,14,1,1,30)
        'T' -> intArrayOf(31,4,4,4,4,4,4)
        'U' -> intArrayOf(17,17,17,17,17,17,14)
        'V' -> intArrayOf(17,17,17,17,17,10,4)
        'W' -> intArrayOf(17,17,17,21,21,27,17)
        'X' -> intArrayOf(17,17,10,4,10,17,17)
        'Y' -> intArrayOf(17,17,10,4,4,4,4)
        'Z' -> intArrayOf(31,1,2,4,8,16,31)
        '0' -> intArrayOf(14,17,19,21,25,17,14)
        '1' -> intArrayOf(4,12,4,4,4,4,14)
        '2' -> intArrayOf(14,17,1,2,4,8,31)
        '3' -> intArrayOf(30,1,1,14,1,1,30)
        '4' -> intArrayOf(2,6,10,18,31,2,2)
        '5' -> intArrayOf(31,16,16,30,1,1,30)
        '6' -> intArrayOf(14,16,16,30,17,17,14)
        '7' -> intArrayOf(31,1,2,4,8,8,8)
        '8' -> intArrayOf(14,17,17,14,17,17,14)
        '9' -> intArrayOf(14,17,17,15,1,1,14)
        '-' -> intArrayOf(0,0,0,31,0,0,0)
        '+' -> intArrayOf(0,4,4,31,4,4,0)
        '.' -> intArrayOf(0,0,0,0,0,6,6)
        ':' -> intArrayOf(0,6,6,0,6,6,0)
        '/' -> intArrayOf(1,2,2,4,8,8,16)
        '%' -> intArrayOf(17,2,4,4,8,16,17)
        ',' -> intArrayOf(0,0,0,0,0,6,4)
        '×' -> intArrayOf(17,10,4,10,17,0,0)
        ' ' -> IntArray(7)
        else -> intArrayOf(14,17,1,2,4,0,4)
    } }

    private fun drawLinkLed(canvas: Canvas) {
        val centerX = 604f
        val centerY = 1602f
        canvas.drawCircle(centerX, centerY, 13f, ledBezelPaint)
        val now = SystemClock.uptimeMillis()
        val phase = (now % 900L) / 900f * (Math.PI * 2.0)
        val pulse = ((kotlin.math.sin(phase).toFloat() + 1f) * .5f)
        val color = when (linkState) {
            LinkState.OFF -> Color.rgb(48, 51, 44)
            LinkState.CONNECTING -> Color.rgb(213, 151, 39)
            LinkState.READY -> Color.rgb(48, 139, 68)
            LinkState.PRINTING -> Color.rgb(67, 181, 78)
            LinkState.ERROR -> Color.rgb(177, 54, 44)
        }
        ledPaint.color = color
        ledPaint.alpha = when (linkState) {
            LinkState.CONNECTING -> (85 + pulse * 170).toInt()
            LinkState.PRINTING -> (125 + pulse * 130).toInt()
            else -> 255
        }
        canvas.drawCircle(centerX, centerY, 8f, ledPaint)
        canvas.drawCircle(centerX - 2.5f, centerY - 2.5f, 2.1f, ledGlintPaint)
        ledPaint.alpha = 255
        if (linkState == LinkState.CONNECTING || linkState == LinkState.PRINTING) postInvalidateDelayed(70L)
    }

    private fun drawHelp(canvas: Canvas) {
        canvas.drawRoundRect(helpShadow, 18f, 18f, statusShadowPaint)
        canvas.drawRoundRect(helpBezel, 18f, 18f, statusBezelPaint)
        canvas.drawRoundRect(helpInset, 10f, 10f, statusInsetPaint)
        canvas.drawRoundRect(helpBox, 7f, 7f, helpPanelPaint)
        canvas.drawRoundRect(helpBox, 7f, 7f, statusGlarePaint)

        val left = 174f
        val right = 712f
        val textWidth = right - left
        drawLcdText(canvas, "PETIT COLLE / GUIDE", left, 370f, 3.25f, Paint.Align.LEFT)
        drawLcdText(canvas, "YOUR TINY STICKER CAMERA", left, 414f, 2.1f, Paint.Align.LEFT)
        canvas.drawLine(left, 440f, right, 440f, helpRulePaint)

        helpTextPaint.textSize = 20f
        var y = drawWrappedText(
            canvas,
            "Petit Colle turns your phone and Fichero printer into a tiny instant camera. " +
                "The green viewfinder shows the exact 96 × 192 image that will be printed.",
            left,
            474f,
            textWidth,
            27f,
            helpTextPaint,
        ) + 18f

        val sections = listOf(
            "TAKE A PHOTO" to
                "Press the large white button to freeze and save the picture. Press it again to return to the live camera.",
            "PRINT A STICKER" to
                "Press the yellow PRINT button to capture, save, and print. If a picture is already frozen, it prints that picture.",
            "CHOOSE A LOOK" to
                "Tap DTH to move through the rendering styles. Every newly selected style begins in AUTO; hold DTH to return to AUTO later.",
            "REFINE THE IMAGE" to
                "Tap MODE to use the latest automatic values, then step through Light, Contrast, Cut, Error, and Zoom. Use the arrows to adjust the selected value.",
            "CONNECT AND SWITCH" to
                "Tap LINK to find your Fichero printer. Tap the main viewfinder to switch between the front and rear cameras.",
            "RESET CONTROLS" to
                "Hold either arrow to reset the selected value. Hold MODE to restore every setting for the current rendering style.",
        )
        sections.forEach { (title, body) ->
            helpLabelPaint.textSize = 20f
            canvas.drawText(title, left, y, helpLabelPaint)
            helpTextPaint.textSize = 19.5f
            y = drawWrappedText(canvas, body, left, y + 29f, textWidth, 25.5f, helpTextPaint)
            canvas.drawLine(left, y + 2f, right, y + 2f, helpRulePaint)
            y += 29f
        }

        drawLcdText(canvas, "TAP ANYWHERE TO CLOSE", helpBox.centerX(), 1408f, 2.2f, Paint.Align.CENTER)
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        left: Float,
        firstBaseline: Float,
        maximumWidth: Float,
        lineHeight: Float,
        paint: Paint,
    ): Float {
        var remaining = text.trim()
        var baseline = firstBaseline
        while (remaining.isNotEmpty()) {
            var count = paint.breakText(remaining, true, maximumWidth, null)
            if (count < remaining.length) {
                val wordBreak = remaining.lastIndexOf(' ', count - 1)
                if (wordBreak > 0) count = wordBreak
            }
            canvas.drawText(remaining.substring(0, count), left, baseline, paint)
            remaining = remaining.substring(count).trimStart()
            baseline += lineHeight
        }
        return baseline
    }

    private fun drawEdgeBlends(canvas: Canvas) {
        canvas.drawRect(artwork.left, artwork.top, artwork.left + edgeFade, artwork.bottom, leftEdgePaint)
        canvas.drawRect(artwork.right - edgeFade, artwork.top, artwork.right, artwork.bottom, rightEdgePaint)
        canvas.drawRect(artwork.left, artwork.top, artwork.right, artwork.top + edgeFade, topEdgePaint)
        canvas.drawRect(artwork.left, artwork.bottom - edgeFade, artwork.right, artwork.bottom, bottomEdgePaint)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.x - dx) / factor
        val y = (event.y - dy) / factor
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = if (showingHelp && artwork.contains(x, y)) dismissHelpTarget
                    else targets.firstOrNull { it.bounds.contains(x, y) }
                return pressed != null
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
            MotionEvent.ACTION_UP -> {
                val target = pressed
                pressed = null
                if (target != null && target.bounds.contains(x, y)) {
                    performClick()
                    val held = event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
                    val longAction = if (held) when (target.action) {
                        CameraAction.INCREASE, CameraAction.DECREASE -> CameraAction.RESET
                        CameraAction.SELECT -> CameraAction.RESET_ALL
                        CameraAction.DITHER -> CameraAction.AUTO_CURRENT
                        else -> null
                    } else null
                    performHapticFeedback(if (longAction != null) android.view.HapticFeedbackConstants.LONG_PRESS else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    (longAction ?: target.action)?.let(onAction)
                }
            }
        }
        return true
    }
}
