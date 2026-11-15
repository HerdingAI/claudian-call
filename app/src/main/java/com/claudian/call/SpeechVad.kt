package com.claudian.call

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.sqrt

data class FrameAcousticStats(
    val rawRms: Float,
    val bandRms: Float,
    val zcr: Float,
    val isSpeechBand: Boolean
)

/**
 * Layer 1 DSP gate:
 * - Isolates the 200 Hz - 3.4 kHz human speech band via 1st-order HPF + LPF.
 * - Measures Zero-Crossing Rate (ZCR) to reject low-frequency bumps (<150 Hz)
 *   and high-frequency hiss/rustling/clicks (>3.5 kHz).
 * - Tracks an adaptive ambient noise floor when idle.
 */
class SpeechBandAnalyzer {
    private var hpPrevX = 0f
    private var hpPrevY = 0f
    private var lpPrevY = 0f
    var noiseFloorRms: Float = 0.005f
        private set

    fun analyze(frame: ShortArray, length: Int = frame.size): FrameAcousticStats {
        if (length <= 0) return FrameAcousticStats(0f, 0f, 0f, false)

        var rawSumSq = 0.0
        var bandSumSq = 0.0
        var crossings = 0
        var prevBand = 0f

        // 16 kHz: HPF alpha=0.92 (~215 Hz), LPF beta=0.58 (~3.4 kHz)
        val hpAlpha = 0.92f
        val lpBeta = 0.58f

        for (i in 0 until length) {
            val x = frame[i] / 32768f
            rawSumSq += x * x

            val hp = hpAlpha * (hpPrevY + x - hpPrevX)
            hpPrevX = x
            hpPrevY = hp

            val lp = lpPrevY + lpBeta * (hp - lpPrevY)
            lpPrevY = lp

            bandSumSq += lp * lp
            if (i > 0 && ((lp >= 0f && prevBand < 0f) || (lp < 0f && prevBand >= 0f))) {
                crossings++
            }
            prevBand = lp
        }

        val rawRms = sqrt(rawSumSq / length).toFloat()
        val bandRms = sqrt(bandSumSq / length).toFloat()
        val zcr = crossings.toFloat() / length.toFloat()
        val isSpeechBand = zcr in 0.02f..0.32f
        return FrameAcousticStats(rawRms, bandRms, zcr, isSpeechBand)
    }

    fun updateNoiseFloor(bandRms: Float) {
        // Track ambient noise slowly upward, slightly faster downward
        val rate = if (bandRms < noiseFloorRms) 0.08f else 0.02f
        noiseFloorRms = (noiseFloorRms * (1f - rate) + bandRms * rate).coerceIn(0.002f, 0.06f)
    }
}

/**
 * Layer 2 neural VAD:
 * Runs Silero VAD v6 (512-sample 32ms frames @ 16 kHz with 64-sample context)
 * on-device via ONNX Runtime to distinguish human speech from non-speech noise.
 */
class SileroVadDetector(context: Context) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val hData = FloatArray(128)
    private val cData = FloatArray(128)
    private val contextBuf = FloatArray(64)
    private val inputBuf = FloatArray(576)

    init {
        val modelBytes = context.assets.open("silero_vad_v6.onnx").use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }
        session = env.createSession(modelBytes, opts)
    }

    fun reset() {
        hData.fill(0f)
        cData.fill(0f)
        contextBuf.fill(0f)
    }

    fun processFrame(frame: ShortArray): Float {
        if (frame.size < 512) return 0f

        contextBuf.copyInto(inputBuf, 0, 0, 64)
        for (i in 0 until 512) {
            inputBuf[64 + i] = frame[i] / 32768f
        }
        inputBuf.copyInto(contextBuf, 0, 512, 576)

        val xTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputBuf), longArrayOf(1L, 576L))
        val hTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(hData), longArrayOf(1L, 1L, 128L))
        val cTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(cData), longArrayOf(1L, 1L, 128L))

        return try {
            val inputs = mapOf("input" to xTensor, "h" to hTensor, "c" to cTensor)
            session.run(inputs).use { results ->
                val probRaw = results[0].value
                val prob = when (probRaw) {
                    is FloatArray -> probRaw.firstOrNull() ?: 0f
                    is Array<*> -> (probRaw.firstOrNull() as? FloatArray)?.firstOrNull() ?: 0f
                    else -> 0f
                }
                val hn = results[1].value as? Array<*>
                val hnVec = (hn?.firstOrNull() as? Array<*>)?.firstOrNull() as? FloatArray
                if (hnVec != null && hnVec.size == 128) hnVec.copyInto(hData)

                val cn = results[2].value as? Array<*>
                val cnVec = (cn?.firstOrNull() as? Array<*>)?.firstOrNull() as? FloatArray
                if (cnVec != null && cnVec.size == 128) cnVec.copyInto(cData)

                prob
            }
        } finally {
            xTensor.close()
            hTensor.close()
            cTensor.close()
        }
    }

    override fun close() {
        try {
            session.close()
        } catch (_: Throwable) {}
    }
}
