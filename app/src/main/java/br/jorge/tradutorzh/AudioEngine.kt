package br.jorge.tradutorzh

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Captura o microfone a 16 kHz mono (formato nativo do modelo Vosk) e alimenta
 * o reconhecedor de mandarim em blocos de 100 ms, numa thread de alta prioridade.
 */
class AudioEngine(private val model: Model, private val listener: Listener) {

    interface Listener {
        fun onPartial(zh: String)
        fun onFinal(zh: String)
        fun onLevel(level: Float)
        fun onError(msg: String)
        fun onSourceUsed(source: Int)
    }

    @Volatile var gain = 1.0f
    /** Quando true (ex.: TTS falando), o áudio é descartado para não traduzir a própria voz do app. */
    @Volatile var isMuted: () -> Boolean = { false }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun isRunning() = running

    fun start(source: Int, device: AudioDeviceInfo?, noiseSuppressor: Boolean) {
        if (running) return
        running = true
        thread = Thread({ loop(source, device, noiseSuppressor) }, "zh-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(source: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = max(minBuf, SR) // ~0,5 s de folga para não perder amostras
        return try {
            val r = AudioRecord(source, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        } catch (e: Exception) { null }
    }

    private fun loop(requestedSource: Int, device: AudioDeviceInfo?, useNs: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        var source = requestedSource
        var rec = openRecord(source)
        if (rec == null && source != MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            source = MediaRecorder.AudioSource.VOICE_RECOGNITION
            rec = openRecord(source)
        }
        if (rec == null) {
            running = false
            listener.onError("Não foi possível abrir o microfone.")
            return
        }
        listener.onSourceUsed(source)
        if (device != null) rec.preferredDevice = device

        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        try {
            if (useNs && NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
            }
            // AGC do sistema bombeia o ruído entre frases e atrapalha o reconhecimento: desligado
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = false }
            }
        } catch (_: Exception) { }

        val recognizer = try { Recognizer(model, SR.toFloat()) } catch (e: Exception) {
            rec.release(); running = false
            listener.onError("Erro no reconhecedor: ${e.message}")
            return
        }

        val buf = ShortArray(SR / 10) // 100 ms
        var lastPartial = ""
        // filtro passa-altas (~80 Hz) para tirar ronco/vento/vibração da mesa
        var xPrev = 0f; var yPrev = 0f
        val a = 0.969f

        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue

                val g = gain
                var sum = 0.0
                for (i in 0 until n) {
                    val x = buf[i].toFloat()
                    val y = x - xPrev + a * yPrev
                    xPrev = x; yPrev = y
                    val v = (y * g).toInt().coerceIn(-32768, 32767)
                    buf[i] = v.toShort()
                    sum += v.toDouble() * v
                }
                val rms = (sqrt(sum / n) / 32768.0).toFloat()
                listener.onLevel((rms * 5f).coerceAtMost(1f))

                if (isMuted()) continue

                if (recognizer.acceptWaveForm(buf, n)) {
                    val txt = clean(JSONObject(recognizer.result).optString("text"))
                    lastPartial = ""
                    if (txt.isNotEmpty()) listener.onFinal(txt)
                } else {
                    val p = clean(JSONObject(recognizer.partialResult).optString("partial"))
                    if (p != lastPartial) {
                        lastPartial = p
                        listener.onPartial(p)
                    }
                }
            }
            val last = clean(JSONObject(recognizer.finalResult).optString("text"))
            if (last.isNotEmpty()) listener.onFinal(last)
        } catch (e: Exception) {
            listener.onError("Falha na captura: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) { }
            rec.release()
            ns?.release(); agc?.release()
            recognizer.close()
            running = false
        }
    }

    /** O modelo chinês devolve palavras separadas por espaço; o chinês escrito não usa espaços. */
    private fun clean(s: String) = s.replace(Regex("\\s+"), "")

    companion object { const val SR = 16000 }
}
