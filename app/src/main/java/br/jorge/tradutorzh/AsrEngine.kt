package br.jorge.tradutorzh

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Reconhecimento de mandarim em duas passagens, 100% offline:
 *  1ª passagem: Zipformer streaming (legenda instantânea enquanto a pessoa fala)
 *  2ª passagem: SenseVoice sobre a frase inteira (texto final mais preciso)
 */
class AsrEngine(assets: AssetManager) {

    interface Listener {
        fun onPartial(zh: String)
        fun onFinal(zh: String)
        fun onLevel(level: Float)
        fun onError(msg: String)
        fun onSourceUsed(source: Int)
    }

    private val online: OnlineRecognizer
    private val offline: OfflineRecognizer
    private val secondPass = Executors.newSingleThreadExecutor()

    init {
        online = OnlineRecognizer(
            assetManager = assets,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$STREAM_DIR/encoder.int8.onnx",
                        decoder = "$STREAM_DIR/decoder.onnx",
                        joiner = "$STREAM_DIR/joiner.int8.onnx",
                    ),
                    tokens = "$STREAM_DIR/tokens.txt",
                    modelType = "zipformer2",
                    numThreads = 2,
                ),
                // fecha a frase após 0,8 s de silêncio; força corte em frases de 15 s
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.0f, 0.0f),
                    rule2 = EndpointRule(true, 0.8f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 15.0f),
                ),
                enableEndpoint = true,
            ),
        )
        offline = OfflineRecognizer(
            assetManager = assets,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$SENSEVOICE_DIR/model.int8.onnx",
                        language = "zh",
                        useInverseTextNormalization = true,
                    ),
                    tokens = "$SENSEVOICE_DIR/tokens.txt",
                    numThreads = 2,
                ),
            ),
        )
    }

    @Volatile var gain = 1.0f
    /** Quando true (TTS falando), o áudio é descartado para não traduzir a voz do próprio app. */
    @Volatile var isMuted: () -> Boolean = { false }
    @Volatile private var running = false
    private var thread: Thread? = null
    private var listener: Listener? = null

    fun isRunning() = running

    fun start(source: Int, device: AudioDeviceInfo?, noiseSuppressor: Boolean, l: Listener) {
        if (running) return
        listener = l
        running = true
        thread = Thread({ loop(source, device, noiseSuppressor, l) }, "zh-asr").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
    }

    fun release() {
        stop()
        secondPass.shutdown()
        online.release()
        offline.release()
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(source: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return try {
            val r = AudioRecord(source, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SR * 2))
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        } catch (e: Exception) { null }
    }

    private fun loop(requested: Int, device: AudioDeviceInfo?, useNs: Boolean, l: Listener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        var source = requested
        var rec = openRecord(source)
        if (rec == null && source != MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            source = MediaRecorder.AudioSource.VOICE_RECOGNITION
            rec = openRecord(source)
        }
        if (rec == null) { running = false; l.onError("Não foi possível abrir o microfone."); return }
        l.onSourceUsed(source)
        if (device != null) rec.preferredDevice = device

        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        try {
            if (useNs && NoiseSuppressor.isAvailable())
                ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
            // o AGC do sistema "bombeia" o ruído entre frases e piora o reconhecimento
            if (AutomaticGainControl.isAvailable())
                agc = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = false }
        } catch (_: Exception) { }

        val stream = online.createStream()
        val buf = ShortArray(SR / 10) // blocos de 100 ms
        val utterance = FloatArrayBuilder(SR * 16)
        var lastPartial = ""
        // passa-altas ~80 Hz: tira ronco de ar-condicionado, vento e batidas na mesa
        var xPrev = 0f; var yPrev = 0f
        val a = 0.969f

        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue

                val g = gain
                val samples = FloatArray(n)
                var sum = 0.0
                for (i in 0 until n) {
                    val x = buf[i] / 32768f
                    val y = x - xPrev + a * yPrev
                    xPrev = x; yPrev = y
                    val v = (y * g).coerceIn(-1f, 1f)
                    samples[i] = v
                    sum += (v * v).toDouble()
                }
                l.onLevel((sqrt(sum / n).toFloat() * 5f).coerceAtMost(1f))
                if (isMuted()) continue

                utterance.add(samples)
                stream.acceptWaveform(samples, SR)
                while (online.isReady(stream)) online.decode(stream)

                val text = clean(online.getResult(stream).text)
                if (text != lastPartial) { lastPartial = text; l.onPartial(text) }

                if (online.isEndpoint(stream)) {
                    online.reset(stream)
                    val audio = utterance.takeAll()
                    if (text.isNotEmpty()) runSecondPass(audio, text, l)
                    lastPartial = ""
                }
            }
            if (lastPartial.isNotEmpty()) runSecondPass(utterance.takeAll(), lastPartial, l)
        } catch (e: Exception) {
            l.onError("Falha na captura: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) { }
            rec.release()
            ns?.release(); agc?.release()
            stream.release()
            running = false
        }
    }

    /** Reconhece a frase inteira com o SenseVoice (mais preciso) sem travar a captura. */
    private fun runSecondPass(audio: FloatArray, firstPass: String, l: Listener) {
        secondPass.execute {
            val text = try {
                val s = offline.createStream()
                s.acceptWaveform(audio, SR)
                offline.decode(s)
                val r = clean(offline.getResult(s).text)
                s.release()
                r
            } catch (e: Exception) { "" }
            l.onFinal(if (text.isNotEmpty()) text else firstPass)
        }
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), "").trim()

    private class FloatArrayBuilder(cap: Int) {
        private var data = FloatArray(cap)
        private var size = 0
        fun add(x: FloatArray) {
            if (size + x.size > data.size) data = data.copyOf(max(data.size * 2, size + x.size))
            System.arraycopy(x, 0, data, size, x.size)
            size += x.size
        }
        fun takeAll(): FloatArray { val r = data.copyOf(size); size = 0; return r }
    }

    companion object {
        const val SR = 16000
        const val STREAM_DIR = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30"
        const val SENSEVOICE_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"
    }
}
