package com.petitcolle.camera

import androidx.camera.core.ImageProxy
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.sin

const val PRINT_WIDTH = 96
const val PRINT_HEIGHT = 192

enum class DitherMode(val label: String) {
    ATKINSON("MAC"), FLOYD("FLOYD"), STUCKI("STUCKI"), BLUE("BLUE"), BAYER4("DOT"),
    CLUSTER("CLSTR"), LOCAL("LOCAL"), HALFTONE("HALF"), THRESHOLD("CUT"), EDGE("EDGE"),
    ASCII("ASCII"), STIPPLE("STIPPLE"), CONTOUR("CONTOUR");
    fun next() = entries[(ordinal + 1) % entries.size]
}

data class RenderSettings(
    val exposure: Float = 0f,
    val contrast: Float = 1f,
    val threshold: Float = .5f,
    val zoom: Float = 1f,
    val errorDiffusion: Float = 1f,
    val toneCurve: ByteArray? = null,
    val auto: Boolean = true,
    val dither: DitherMode = DitherMode.ATKINSON,
)

/** A complete snapshot of AUTO, reused unchanged for saving and manual refinement. */
data class AutoCalibration(
    val toneCurve: ByteArray,
    val threshold: Float,
    val detailBoost: Float,
)

data class ThermalFrame(
    val dots: ByteArray,
    val calibration: AutoCalibration? = null,
)

data class RenderedPhoto(val width: Int, val height: Int, val dots: ByteArray)

object ThermalRenderer {
    private data class AutoState(var low: Float, var high: Float, var median: Float, var initialized: Boolean = false)
    private data class ToneResult(val settings: RenderSettings, val calibration: AutoCalibration?)
    private data class DiffusionTap(val dx: Int, val dy: Int, val weight: Float)
    private data class BitmapGlyph(val mask: Int, val blackPixels: Int)
    private data class GeometryMaster(val tones: FloatArray, val edges: FloatArray)
    private val asciiRamp by lazy {
        listOf(
            bitmapGlyph("000","000","000","000","000"), // space
            bitmapGlyph("000","000","000","000","010"), // .
            bitmapGlyph("000","010","000","010","000"), // :
            bitmapGlyph("000","000","111","000","000"), // -
            bitmapGlyph("000","111","000","111","000"), // =
            bitmapGlyph("010","010","111","010","010"), // +
            bitmapGlyph("101","010","111","010","101"), // *
            bitmapGlyph("111","101","111","100","110"), // @
            bitmapGlyph("101","111","101","111","101"), // #
        ).sortedBy { it.blackPixels }
    }
    private val autoStates = mutableMapOf<DitherMode, AutoState>()

    fun resetAuto(mode: DitherMode) {
        synchronized(autoStates) { autoStates.remove(mode) }
    }

    private val bayer4 = arrayOf(
        intArrayOf(0, 8, 2, 10), intArrayOf(12, 4, 14, 6),
        intArrayOf(3, 11, 1, 9), intArrayOf(15, 7, 13, 5),
    )
    private val clustered4 = arrayOf(
        intArrayOf(12, 5, 6, 13), intArrayOf(4, 0, 1, 7),
        intArrayOf(11, 3, 2, 8), intArrayOf(15, 10, 9, 14),
    )
    private val blueNoise16 = arrayOf(
        intArrayOf(48,176,23,133,59,254,18,158,36,173,28,245,58,183,20,253),
        intArrayOf(248,75,223,68,244,82,182,118,240,103,174,115,151,106,128,76),
        intArrayOf(10,164,47,232,0,156,41,206,13,208,56,203,2,230,53,192),
        intArrayOf(217,123,172,98,152,74,233,92,129,64,168,67,136,99,177,79),
        intArrayOf(49,221,29,210,35,199,24,190,54,157,30,194,63,202,21,195),
        intArrayOf(186,102,224,122,130,85,198,80,187,125,246,116,204,100,137,108),
        intArrayOf(5,155,57,236,11,161,52,138,4,159,40,175,8,167,61,218),
        intArrayOf(222,66,227,101,235,109,243,104,214,83,197,84,132,72,228,119),
        intArrayOf(62,241,31,147,55,226,27,220,45,234,19,134,34,166,17,207),
        intArrayOf(231,90,229,126,189,114,150,77,135,121,140,73,216,117,225,70),
        intArrayOf(12,215,37,200,3,196,32,160,14,239,60,209,1,169,42,171),
        intArrayOf(154,111,148,69,142,88,170,112,255,86,143,124,185,78,219,107),
        intArrayOf(51,237,26,201,50,188,25,163,44,250,22,205,39,184,16,212),
        intArrayOf(247,87,238,96,178,81,181,95,249,97,144,94,252,110,141,113),
        intArrayOf(7,131,43,213,9,153,33,180,6,191,46,242,15,145,38,162),
        intArrayOf(251,105,179,91,149,93,146,71,165,89,139,127,193,65,211,120),
    )
    private val floyd = listOf(
        DiffusionTap(1,0,7f/16f), DiffusionTap(-1,1,3f/16f),
        DiffusionTap(0,1,5f/16f), DiffusionTap(1,1,1f/16f),
    )
    private val atkinson = listOf(
        DiffusionTap(1,0,1f/8f), DiffusionTap(2,0,1f/8f),
        DiffusionTap(-1,1,1f/8f), DiffusionTap(0,1,1f/8f), DiffusionTap(1,1,1f/8f),
        DiffusionTap(0,2,1f/8f),
    )
    private val stucki = listOf(
        DiffusionTap(1,0,8f/42f), DiffusionTap(2,0,4f/42f),
        DiffusionTap(-2,1,2f/42f), DiffusionTap(-1,1,4f/42f), DiffusionTap(0,1,8f/42f), DiffusionTap(1,1,4f/42f), DiffusionTap(2,1,2f/42f),
        DiffusionTap(-2,2,1f/42f), DiffusionTap(-1,2,2f/42f), DiffusionTap(0,2,4f/42f), DiffusionTap(1,2,2f/42f), DiffusionTap(2,2,1f/42f),
    )

    fun process(image: ImageProxy, settings: RenderSettings, mirrored: Boolean = false): ThermalFrame {
        // The print path stays deliberately small and direct: the preview is the printer raster.
        val luminance = sampleAreaLuminance(image, settings.zoom, mirrored, PRINT_WIDTH, PRINT_HEIGHT)
        val tone = prepareTone(luminance, settings, PRINT_WIDTH, PRINT_HEIGHT)
        return ThermalFrame(
            dither(luminance, tone.settings, PRINT_WIDTH, PRINT_HEIGHT),
            tone.calibration,
        )
    }

    fun processHighResolution(
        image: ImageProxy,
        settings: RenderSettings,
        mirrored: Boolean,
        calibration: AutoCalibration?,
        maxWidth: Int = 640,
    ): RenderedPhoto {
        val pixelStyle = settings.dither !in setOf(
            DitherMode.HALFTONE, DitherMode.ASCII, DitherMode.EDGE,
            DitherMode.STIPPLE, DitherMode.CONTOUR,
        )
        return if (pixelStyle) {
            // Dither at a visible logical resolution, then enlarge the binary artwork cleanly.
            val logicalWidth = 384
            val logicalHeight = logicalWidth * 2
            val luminance = sampleAreaLuminance(image, settings.zoom, mirrored, logicalWidth, logicalHeight)
            val effective = applyCapturedTone(luminance, settings, calibration, logicalWidth, logicalHeight)
            val logicalDots = dither(luminance, effective, logicalWidth, logicalHeight)
            val scale = 3
            RenderedPhoto(logicalWidth * scale, logicalHeight * scale, upscaleNearest(logicalDots, logicalWidth, logicalHeight, scale))
        } else {
            // Procedural geometry is rerendered at high resolution with the same relative frequency.
            val rotation = image.imageInfo.rotationDegrees
            val uprightWidth = if (rotation == 90 || rotation == 270) image.height else image.width
            val uprightHeight = if (rotation == 90 || rotation == 270) image.width else image.height
            val sourceWidth = min(min(uprightWidth.toFloat(), uprightHeight / 2f).toInt(), maxWidth).coerceAtLeast(PRINT_WIDTH)
            val sourceHeight = sourceWidth * 2
            val source = sampleAreaLuminance(image, settings.zoom, mirrored, sourceWidth, sourceHeight)
            val effective = applyCapturedTone(source, settings, calibration, sourceWidth, sourceHeight)
            val outputWidth = 1152
            val outputHeight = 2304
            val enlarged = resizeBilinear(source, sourceWidth, sourceHeight, outputWidth, outputHeight)
            RenderedPhoto(outputWidth, outputHeight, dither(enlarged, effective, outputWidth, outputHeight))
        }
    }

    private fun applyCapturedTone(
        luminance: FloatArray,
        settings: RenderSettings,
        calibration: AutoCalibration?,
        width: Int,
        height: Int,
    ): RenderSettings {
        return if (settings.auto && calibration != null) {
            applyCurve(luminance, calibration.toneCurve)
            sharpenLaplacian(luminance, width, height, calibration.detailBoost)
            settings.copy(threshold=calibration.threshold, toneCurve=calibration.toneCurve)
        } else {
            applyManualTone(luminance, settings, width, height)
            settings
        }
    }

    private fun upscaleNearest(source: ByteArray, width: Int, height: Int, scale: Int): ByteArray {
        val outputWidth = width * scale
        val result = ByteArray(outputWidth * height * scale)
        for (y in 0 until height) for (x in 0 until width) {
            val value = source[y * width + x]
            val top = y * scale
            val left = x * scale
            for (dy in 0 until scale) for (dx in 0 until scale) result[(top + dy) * outputWidth + left + dx] = value
        }
        return result
    }

    private fun resizeBilinear(source: FloatArray, sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): FloatArray {
        val result = FloatArray(width * height)
        for (y in 0 until height) {
            val sy = ((y + .5f) * sourceHeight / height - .5f).coerceIn(0f, sourceHeight - 1f)
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(sourceHeight - 1)
            val fy = sy - y0
            for (x in 0 until width) {
                val sx = ((x + .5f) * sourceWidth / width - .5f).coerceIn(0f, sourceWidth - 1f)
                val x0 = sx.toInt()
                val x1 = (x0 + 1).coerceAtMost(sourceWidth - 1)
                val fx = sx - x0
                val top = source[y0 * sourceWidth + x0] * (1f - fx) + source[y0 * sourceWidth + x1] * fx
                val bottom = source[y1 * sourceWidth + x0] * (1f - fx) + source[y1 * sourceWidth + x1] * fx
                result[y * width + x] = top * (1f - fy) + bottom * fy
            }
        }
        return result
    }

    /** Uses every camera pixel contributing to an output cell instead of sparse point samples. */
    private fun sampleAreaLuminance(image: ImageProxy, zoom: Float, mirrored: Boolean, outputWidth: Int, outputHeight: Int): FloatArray {
        val source = image.planes[0]
        val inputWidth = image.width
        val inputHeight = image.height
        val rotation = image.imageInfo.rotationDegrees
        val uprightWidth = if (rotation == 90 || rotation == 270) inputHeight else inputWidth
        val uprightHeight = if (rotation == 90 || rotation == 270) inputWidth else inputHeight
        val baseCropWidth = min(uprightWidth.toFloat(), uprightHeight / 2f)
        val cropWidth = baseCropWidth / zoom
        val cropHeight = cropWidth * 2f
        val cropLeft = (uprightWidth - cropWidth) / 2f
        val cropTop = (uprightHeight - cropHeight) / 2f
        val result = FloatArray(outputWidth * outputHeight)
        // The live raster covers large source cells. A 4x4 stratified box approximation is
        // much faster than visiting ~45 source pixels per thermal dot and preserves edges well.
        if (cropWidth / outputWidth > 4f) {
            val samples = 4
            for (y in 0 until outputHeight) for (x in 0 until outputWidth) {
                var sum = 0
                for (sy in 0 until samples) for (sx in 0 until samples) {
                    val ux = floor(cropLeft + (x + (sx + .5f) / samples) * cropWidth / outputWidth)
                        .toInt().coerceIn(0, uprightWidth - 1)
                    val uy = floor(cropTop + (y + (sy + .5f) / samples) * cropHeight / outputHeight)
                        .toInt().coerceIn(0, uprightHeight - 1)
                    val sampledX = if (mirrored) uprightWidth - 1 - ux else ux
                    val offset = sourceOffset(sampledX, uy, inputWidth, inputHeight, rotation, source.rowStride, source.pixelStride)
                    sum += source.buffer.get(offset).toInt() and 0xFF
                }
                result[y * outputWidth + x] = sum / (255f * samples * samples)
            }
            return result
        }
        for (y in 0 until outputHeight) {
            val top = cropTop + y * cropHeight / outputHeight
            val bottom = cropTop + (y + 1) * cropHeight / outputHeight
            val firstY = floor(top).toInt().coerceIn(0, uprightHeight - 1)
            val lastY = (ceil(bottom).toInt() - 1).coerceIn(firstY, uprightHeight - 1)
            for (x in 0 until outputWidth) {
                val left = cropLeft + x * cropWidth / outputWidth
                val right = cropLeft + (x + 1) * cropWidth / outputWidth
                val firstX = floor(left).toInt().coerceIn(0, uprightWidth - 1)
                val lastX = (ceil(right).toInt() - 1).coerceIn(firstX, uprightWidth - 1)
                var sum = 0f
                var weights = 0f
                for (uy in firstY..lastY) {
                    val wy = (min(bottom, uy + 1f) - max(top, uy.toFloat())).coerceAtLeast(0f)
                    for (ux in firstX..lastX) {
                        val wx = (min(right, ux + 1f) - max(left, ux.toFloat())).coerceAtLeast(0f)
                        val weight = wx * wy
                        val sampledX = if (mirrored) uprightWidth - 1 - ux else ux
                        val offset = sourceOffset(sampledX, uy, inputWidth, inputHeight, rotation, source.rowStride, source.pixelStride)
                        sum += (source.buffer.get(offset).toInt() and 0xFF) * weight
                        weights += weight
                    }
                }
                result[y * outputWidth + x] = if (weights > 0f) sum / (255f * weights) else 0f
            }
        }
        return result
    }

    private fun prepareTone(luminance: FloatArray, settings: RenderSettings, width: Int, height: Int): ToneResult =
        if (settings.auto) autoCalibrate(luminance, settings, width, height) else {
            applyManualTone(luminance, settings, width, height)
            ToneResult(settings, null)
        }

    private fun applyManualTone(luminance: FloatArray, settings: RenderSettings, width: Int, height: Int) {
        settings.toneCurve?.let { applyCurve(luminance, it) }
        luminance.indices.forEach { i ->
            luminance[i] = ((luminance[i] - .5f) * settings.contrast + .5f + settings.exposure).coerceIn(0f,1f)
        }
        sharpenLaplacian(luminance, width, height, .18f)
    }

    private fun autoCalibrate(luminance: FloatArray, settings: RenderSettings, width: Int, height: Int): ToneResult {
        val histogram = weightedHistogram(luminance, width, height)
        val total = histogram.sum()
        val lowNow = percentile(histogram,total,.02f)/255f
        val highNow = percentile(histogram,total,.98f)/255f
        val medianNow = percentile(histogram,total,.50f)/255f
        val midpoint = (lowNow + highNow) * .5f
        val minimumRange = .22f
        val targetLow = if (highNow-lowNow < minimumRange) midpoint-minimumRange*.5f else lowNow
        val targetHigh = if (highNow-lowNow < minimumRange) midpoint+minimumRange*.5f else highNow
        val state = synchronized(autoStates) { autoStates.getOrPut(settings.dither) { AutoState(targetLow,targetHigh,medianNow) } }
        val sceneChange = abs(targetLow-state.low)+abs(targetHigh-state.high)+abs(medianNow-state.median) > .24f
        val alpha = if (!state.initialized || sceneChange) 1f else .18f
        state.low += (targetLow-state.low)*alpha
        state.high += (targetHigh-state.high)*alpha
        state.median += (medianNow-state.median)*alpha
        state.initialized = true
        val curve = buildToneCurve(state.low,state.high,state.median)
        applyCurve(luminance,curve)
        val detailBoost = when (settings.dither) {
            DitherMode.THRESHOLD -> .12f
            DitherMode.HALFTONE, DitherMode.ASCII, DitherMode.EDGE,
            DitherMode.STIPPLE, DitherMode.CONTOUR -> .14f
            else -> .20f
        }
        sharpenLaplacian(luminance,width,height,detailBoost)
        val calibration = AutoCalibration(curve,.5f,detailBoost)
        return ToneResult(settings.copy(threshold=.5f,toneCurve=curve),calibration)
    }

    private fun weightedHistogram(luminance: FloatArray, width: Int, height: Int): IntArray {
        val histogram = IntArray(256)
        val x0=width/5; val x1=width-x0; val y0=height/5; val y1=height-y0
        for (y in 0 until height) for (x in 0 until width) {
            val value=(luminance[y*width+x]*255f).roundToInt().coerceIn(0,255)
            histogram[value] += if (x in x0 until x1 && y in y0 until y1) 2 else 1
        }
        return histogram
    }

    private fun buildToneCurve(low: Float, high: Float, median: Float): ByteArray {
        val range=(high-low).coerceAtLeast(.20f)
        val normalizedMedian=((median-low)/range).coerceIn(.08f,.92f)
        val gamma=(ln(.52f)/ln(normalizedMedian)).coerceIn(.58f,1.7f)
        val contrast=1.16f
        return ByteArray(256) { value ->
            val normalized=((value/255f-low)/range).coerceIn(0f,1f)
            val corrected=normalized.pow(gamma)
            val output=((corrected-.5f)*contrast+.5f).coerceIn(0f,1f)
            (output*255f).roundToInt().coerceIn(0,255).toByte()
        }
    }

    private fun applyCurve(luminance: FloatArray, curve: ByteArray) {
        luminance.indices.forEach { i ->
            val position=(luminance[i]*255f).coerceIn(0f,255f)
            val lower=position.toInt(); val upper=(lower+1).coerceAtMost(255); val fraction=position-lower
            val a=curve[lower].toInt() and 0xFF; val b=curve[upper].toInt() and 0xFF
            luminance[i]=(a+(b-a)*fraction)/255f
        }
    }

    private fun sharpenLaplacian(luminance: FloatArray, width: Int, height: Int, amount: Float) {
        if (amount <= 0f) return
        val source=luminance.copyOf()
        for (y in 0 until height) for (x in 0 until width) {
            val center=source[y*width+x]
            val left=source[y*width+(x-1).coerceAtLeast(0)]
            val right=source[y*width+(x+1).coerceAtMost(width-1)]
            val top=source[(y-1).coerceAtLeast(0)*width+x]
            val bottom=source[(y+1).coerceAtMost(height-1)*width+x]
            val laplacian=4f*center-left-right-top-bottom
            luminance[y*width+x]=(center+amount*laplacian).coerceIn(0f,1f)
        }
    }

    private fun percentile(histogram: IntArray,total: Int,fraction: Float): Int {
        val target=(total*fraction).roundToInt().coerceAtLeast(1)
        var cumulative=0
        histogram.forEachIndexed { value,count -> cumulative+=count; if (cumulative>=target) return value }
        return 255
    }

    private fun sourceOffset(x:Int,y:Int,sourceWidth:Int,sourceHeight:Int,rotation:Int,rowStride:Int,pixelStride:Int):Int {
        val rawX:Int; val rawY:Int
        when(rotation) {
            90 -> { rawX=y; rawY=sourceHeight-1-x }
            180 -> { rawX=sourceWidth-1-x; rawY=sourceHeight-1-y }
            270 -> { rawX=sourceWidth-1-y; rawY=x }
            else -> { rawX=x; rawY=y }
        }
        return rawY*rowStride+rawX*pixelStride
    }

    private fun dither(luminance:FloatArray,settings:RenderSettings,width:Int,height:Int):ByteArray {
        val result=ByteArray(luminance.size)
        when(settings.dither) {
            DitherMode.THRESHOLD -> luminance.indices.forEach { result[it]=if(luminance[it]<settings.threshold)1 else 0 }
            DitherMode.LOCAL -> adaptive(luminance,result,settings.threshold,width,height)
            DitherMode.BAYER4 -> ordered(luminance,result,settings.threshold,bayer4,width,height)
            DitherMode.BLUE -> ordered(luminance,result,settings.threshold,blueNoise16,width,height)
            DitherMode.CLUSTER -> ordered(luminance,result,settings.threshold,clustered4,width,height)
            DitherMode.HALFTONE -> halftone(luminance,result,settings.threshold,width,height)
            DitherMode.EDGE -> edgeIllustration(luminance,result,settings.threshold,width,height)
            DitherMode.ASCII -> ascii(luminance,result,settings.threshold,width,height)
            DitherMode.STIPPLE -> stipple(luminance,result,settings.threshold,width,height)
            DitherMode.CONTOUR -> contour(luminance,result,settings.threshold,width,height)
            DitherMode.FLOYD -> diffuse(luminance,result,settings,floyd,width,height)
            DitherMode.ATKINSON -> diffuse(luminance,result,settings,atkinson,width,height)
            DitherMode.STUCKI -> diffuse(luminance,result,settings,stucki,width,height)
        }
        return result
    }

    private fun halftone(luminance:FloatArray,result:ByteArray,threshold:Float,width:Int,height:Int) {
        val cell=(width/24f).roundToInt().coerceAtLeast(4)
        for(top in 0 until height step cell) for(left in 0 until width step cell) {
            val right=(left+cell).coerceAtMost(width); val bottom=(top+cell).coerceAtMost(height)
            var sum=0f
            for(y in top until bottom) for(x in left until right) sum+=luminance[y*width+x]
            val average=sum/((right-left)*(bottom-top))
            val darkness=(1f-average+(threshold-.5f)*.8f).coerceIn(0f,1f)
            val centerX=(left+right-1)/2f; val centerY=(top+bottom-1)/2f
            val radius=sqrt(darkness.pow(1.35f))*cell*.71f; val radiusSquared=radius*radius
            for(y in top until bottom) for(x in left until right) {
                val dx=x-centerX; val dy=y-centerY
                result[y*width+x]=if(dx*dx+dy*dy<=radiusSquared)1 else 0
            }
        }
    }

    /**
     * The thermal raster uses a heavy 20 x 39 clustered stipple field. Camera-roll
     * artwork is rerendered at 60 x 117 cells with proportionally smaller dots, carrying
     * more source detail without enlarging the coarse print pattern.
     */
    private fun stipple(luminance: FloatArray, result: ByteArray, threshold: Float, width: Int, height: Int) {
        val master = geometryMaster(luminance, width, height)
        val highResolution = width > PRINT_WIDTH
        val densityMultiplier = if (highResolution) 3 else 1
        val columns = 20 * densityMultiplier
        val rows = 39 * densityMultiplier
        val patternScale = width / (PRINT_WIDTH.toFloat() * densityMultiplier)
        val bias = (threshold - .5f) * .55f
        val positions = arrayOf(.30f to .30f, .70f to .70f, .70f to .30f, .30f to .70f)
        for (row in 0 until rows) for (column in 0 until columns) {
            val left = column * width / columns
            val right = (column + 1) * width / columns
            val top = row * height / rows
            val bottom = (row + 1) * height / rows
            var toneSum = 0f
            var edgeSum = 0f
            for (y in top until bottom) for (x in left until right) {
                val index = y * width + x
                toneSum += master.tones[index]
                edgeSum += master.edges[index]
            }
            val count = ((right - left) * (bottom - top)).coerceAtLeast(1)
            val darkness = (1f - toneSum / count + edgeSum / count * .08f + bias).coerceIn(0f, 1f)
            val dotCount = (darkness.pow(.92f) * positions.size).roundToInt().coerceIn(0, positions.size)
            val radius = patternScale * 1.75f * (.50f + .18f * darkness)
            for (dot in 0 until dotCount) {
                val (xFraction, yFraction) = positions[dot]
                val x = left + (right - left) * xFraction
                val y = top + (bottom - top) * yFraction
                drawDisc(result, width, height, x, y, radius)
            }
        }
    }

    /**
     * Iso-luminance contours with a restrained strong-edge overlay. The five contour
     * levels are fixed, so a digital save gains smoother lines rather than more bands.
     */
    private fun contour(luminance: FloatArray, result: ByteArray, threshold: Float, width: Int, height: Int) {
        val master = geometryMaster(luminance, width, height)
        val bias = (threshold - .5f) * .45f
        val levels = floatArrayOf(.22f, .38f, .54f, .70f, .86f)
        for (index in luminance.indices) {
            val tone = (master.tones[index] - bias).coerceIn(0f, 1f)
            var nearest = 1f
            for (level in levels) nearest = min(nearest, abs(tone - level))
            if (nearest < .026f || master.edges[index] > .22f) result[index] = 1
        }
    }

    /**
     * The thermal raster uses a legible 24 x 32 grid. Camera-roll artwork is rerendered
     * with three times as many cells in each direction, preserving ASCII structure while
     * carrying substantially more source detail than an enlarged print raster.
     */
    private fun ascii(luminance: FloatArray, result: ByteArray, threshold: Float, width: Int, height: Int) {
        val master = geometryMaster(luminance, width, height)
        val highResolution = width > PRINT_WIDTH
        val columns = if (highResolution) 72 else 24
        val rows = if (highResolution) 96 else 32
        val bias = (threshold - .5f) * .45f
        val maxBlack = asciiRamp.maxOf { it.blackPixels }
        for (row in 0 until rows) for (column in 0 until columns) {
            val left = column * width / columns
            val right = (column + 1) * width / columns
            val top = row * height / rows
            val bottom = (row + 1) * height / rows
            var toneSum = 0f
            var edgeSum = 0f
            for (y in top until bottom) for (x in left until right) {
                val index = y * width + x
                toneSum += master.tones[index]
                edgeSum += master.edges[index]
            }
            val count = (right - left) * (bottom - top)
            val rawDarkness = (1f - toneSum / count + edgeSum / count * .10f + bias).coerceIn(0f, 1f)
            val darkness = if (rawDarkness < .17f) 0f else ((rawDarkness - .17f) / .83f).coerceIn(0f, 1f).pow(.92f)
            val target = darkness * maxBlack
            val glyph = asciiRamp.minBy { abs(it.blackPixels - target) }
            for (y in top until bottom) for (x in left until right) {
                val virtualX = (x - left) * 4 / (right - left)
                val virtualY = (y - top) * 6 / (bottom - top)
                if (virtualX < 3 && virtualY < 5) {
                    val bit = virtualY * 3 + virtualX
                    if (glyph.mask and (1 shl bit) != 0) result[y * width + x] = 1
                }
            }
        }
    }

    private fun bitmapGlyph(vararg rows: String): BitmapGlyph {
        var mask = 0
        var count = 0
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, value ->
            if (value == '1') {
                mask = mask or (1 shl (y * 3 + x))
                count += 1
            }
        } }
        return BitmapGlyph(mask, count)
    }

    /** Hysteresis-linked contours with restrained deep-shadow screen fill. */
    private fun edgeIllustration(luminance: FloatArray, result: ByteArray, threshold: Float, width: Int, height: Int) {
        val master = geometryMaster(luminance, width, height)
        val scale = width / PRINT_WIDTH.toFloat()
        val highThreshold = (.155f - (threshold - .5f) * .07f).coerceIn(.10f, .21f)
        val lowThreshold = highThreshold * .56f
        val minimumComponent = (3f * scale).roundToInt().coerceAtLeast(3)
        val linked = hysteresisEdges(master.edges, width, height, lowThreshold, highThreshold, minimumComponent)
        val radius = if (width <= PRINT_WIDTH) 0 else (scale * .10f).roundToInt().coerceAtLeast(1)
        val contours = dilate(linked, width, height, radius)
        val screenScale = scale.roundToInt().coerceAtLeast(1)
        val bias = (threshold - .5f) * .42f
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            val darkness = (1f - master.tones[index] + bias).coerceIn(0f, 1f)
            val fillAmount = ((darkness - .70f) / .30f).coerceIn(0f, 1f) * .42f
            val sx = x / screenScale
            val sy = y / screenScale
            val screenThreshold = (bayer4[sy % 4][sx % 4] + .5f) / 16f
            if (contours[index].toInt() != 0 || fillAmount > screenThreshold) result[index] = 1
        }
    }

    private fun hysteresisEdges(
        strength: FloatArray,
        width: Int,
        height: Int,
        low: Float,
        high: Float,
        minimumComponent: Int,
    ): ByteArray {
        val visited = BooleanArray(strength.size)
        val result = ByteArray(strength.size)
        val queue = IntArray(strength.size)
        for (start in strength.indices) {
            if (visited[start] || strength[start] < low) continue
            var head = 0
            var tail = 0
            var hasStrong = false
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val index = queue[head++]
                if (strength[index] >= high) hasStrong = true
                val x = index % width
                val y = index / width
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (!visited[next] && strength[next] >= low) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }
            if (hasStrong && tail >= minimumComponent) for (i in 0 until tail) result[queue[i]] = 1
        }
        return result
    }

    private fun geometryMaster(luminance: FloatArray, width: Int, height: Int): GeometryMaster {
        val scale = width / PRINT_WIDTH.toFloat()
        val radius = (scale * .75f).roundToInt().coerceAtLeast(1)
        val smoothed = boxBlur(luminance, width, height, radius)
        val step = scale.roundToInt().coerceAtLeast(1)
        val edges = FloatArray(luminance.size)
        for (y in step until height - step) for (x in step until width - step) {
            fun at(dx: Int, dy: Int) = smoothed[(y + dy * step) * width + x + dx * step]
            val gx = -at(-1,-1) + at(1,-1) - 2f*at(-1,0) + 2f*at(1,0) - at(-1,1) + at(1,1)
            val gy = -at(-1,-1) - 2f*at(0,-1) - at(1,-1) + at(-1,1) + 2f*at(0,1) + at(1,1)
            edges[y * width + x] = (sqrt(gx * gx + gy * gy) / 4f).coerceIn(0f, 1f)
        }
        return GeometryMaster(smoothed, edges)
    }

    private fun drawDisc(result: ByteArray, width: Int, height: Int, centerX: Float, centerY: Float, radius: Float) {
        val effectiveRadius = max(.55f, radius)
        val left = floor(centerX - effectiveRadius).toInt().coerceAtLeast(0)
        val right = ceil(centerX + effectiveRadius).toInt().coerceAtMost(width - 1)
        val top = floor(centerY - effectiveRadius).toInt().coerceAtLeast(0)
        val bottom = ceil(centerY + effectiveRadius).toInt().coerceAtMost(height - 1)
        val squared = effectiveRadius * effectiveRadius
        var drew = false
        for (y in top..bottom) for (x in left..right) {
            val dx = (x + .5f) - centerX
            val dy = (y + .5f) - centerY
            if (dx * dx + dy * dy <= squared) {
                result[y * width + x] = 1
                drew = true
            }
        }
        if (!drew) {
            val x = centerX.roundToInt()
            val y = centerY.roundToInt()
            if (x in 0 until width && y in 0 until height) result[y * width + x] = 1
        }
    }

    private fun dilate(source: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        val stride = width + 1
        val integral = IntArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var row = 0
            for (x in 0 until width) {
                row += source[y * width + x].toInt()
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
            }
        }
        val result = ByteArray(source.size)
        for (y in 0 until height) for (x in 0 until width) {
            val left = (x - radius).coerceAtLeast(0)
            val right = (x + radius + 1).coerceAtMost(width)
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius + 1).coerceAtMost(height)
            val count = integral[bottom * stride + right] - integral[top * stride + right] -
                integral[bottom * stride + left] + integral[top * stride + left]
            if (count > 0) result[y * width + x] = 1
        }
        return result
    }

    private fun boxBlur(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val stride = width + 1
        val integral = FloatArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0f
            for (x in 0 until width) {
                rowSum += source[y * width + x]
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
            }
        }
        val result = FloatArray(source.size)
        for (y in 0 until height) for (x in 0 until width) {
            val left = (x - radius).coerceAtLeast(0)
            val right = (x + radius + 1).coerceAtMost(width)
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius + 1).coerceAtMost(height)
            val sum = integral[bottom * stride + right] - integral[top * stride + right] -
                integral[bottom * stride + left] + integral[top * stride + left]
            result[y * width + x] = sum / ((right - left) * (bottom - top))
        }
        return result
    }

    private fun adaptive(luminance:FloatArray,result:ByteArray,threshold:Float,width:Int,height:Int) {
        val localMean=boxBlur(luminance,width,height,(5f*sqrt(width/PRINT_WIDTH.toFloat())).roundToInt().coerceAtLeast(5))
        for(i in luminance.indices) {
            val localThreshold=(threshold*.38f+localMean[i]*.62f).coerceIn(.06f,.94f)
            result[i]=if(luminance[i]<localThreshold)1 else 0
        }
    }

    /** A deliberately compressed screen range favors crisp shadows/highlights at thermal resolution. */
    private fun ordered(luminance:FloatArray,result:ByteArray,threshold:Float,matrix:Array<IntArray>,width:Int,height:Int) {
        val size=matrix.size; val levels=size*size
        for(y in 0 until height) for(x in 0 until width) {
            val variation=((matrix[y%size][x%size]+.5f)/levels-.5f)*.70f
            val index=y*width+x
            result[index]=if(luminance[index]<threshold+variation)1 else 0
        }
    }

    /** Serpentine diffusion avoids directional streaks and propagated luma error is not clipped away. */
    private fun diffuse(luminance:FloatArray,result:ByteArray,settings:RenderSettings,taps:List<DiffusionTap>,width:Int,height:Int) {
        val working=luminance.copyOf()
        for(y in 0 until height) {
            val reverse=y%2==1
            val range=if(reverse) width-1 downTo 0 else 0 until width
            for(x in range) {
                val index=y*width+x; val black=working[index]<settings.threshold
                result[index]=if(black)1 else 0
                val error=(working[index]-if(black)0f else 1f)*settings.errorDiffusion
                taps.forEach { tap -> spread(working,x+if(reverse)-tap.dx else tap.dx,y+tap.dy,error*tap.weight,width,height) }
            }
        }
    }

    private fun spread(values:FloatArray,x:Int,y:Int,amount:Float,width:Int,height:Int) {
        if(x in 0 until width && y in 0 until height) values[y*width+x]+=amount
    }
}
