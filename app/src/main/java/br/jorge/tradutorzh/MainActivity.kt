package br.jorge.tradutorzh

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.android.StorageService
import java.util.Locale

class MainActivity : Activity(), AudioEngine.Listener {

    private lateinit var tvStatus: TextView
    private lateinit var tvZhLive: TextView
    private lateinit var tvPtLive: TextView
    private lateinit var tvHistory: TextView
    private lateinit var scroll: ScrollView
    private lateinit var level: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnDownload: Button
    private lateinit var panelSettings: View
    private lateinit var spSource: Spinner
    private lateinit var spDevice: Spinner
    private lateinit var sbGain: SeekBar
    private lateinit var tvGain: TextView
    private lateinit var cbNs: CheckBox
    private lateinit var cbTts: CheckBox

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var model: Model? = null
    private var modelMsg = "Preparando reconhecimento de mandarim…"
    private var audio: AudioEngine? = null
    private val translator = TranslatorEngine()
    private var translatorReady = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var ttsSpeaking = false
    private var utterId = 0

    // tradução "ao vivo" da frase parcial
    private var pendingPartial = ""
    private var lastTranslatedPartial = ""
    private var partialBusy = false
    private var phraseSeq = 0

    private val history = SpannableStringBuilder()

    private data class SourceOpt(val label: String, val source: Int)
    private val sources = listOf(
        SourceOpt("Reconhecimento de voz (recomendado)", MediaRecorder.AudioSource.VOICE_RECOGNITION),
        SourceOpt("Câmera – mic traseiro (apontar para quem fala)", MediaRecorder.AudioSource.CAMCORDER),
        SourceOpt("Bruto, sem processamento", MediaRecorder.AudioSource.UNPROCESSED),
        SourceOpt("Comunicação (menos ruído e eco)", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
        SourceOpt("Padrão do sistema", MediaRecorder.AudioSource.MIC),
    )
    private var devices: List<AudioDeviceInfo?> = listOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupSettings()

        LibVosk.setLogLevel(LogLevel.WARNINGS)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        loadVoiceModel()
        checkTranslator()
        initTts()
        updateStatus()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvZhLive = findViewById(R.id.tvZhLive)
        tvPtLive = findViewById(R.id.tvPtLive)
        tvHistory = findViewById(R.id.tvHistory)
        scroll = findViewById(R.id.scroll)
        level = findViewById(R.id.level)
        btnStart = findViewById(R.id.btnStart)
        btnDownload = findViewById(R.id.btnDownload)
        panelSettings = findViewById(R.id.panelSettings)
        spSource = findViewById(R.id.spSource)
        spDevice = findViewById(R.id.spDevice)
        sbGain = findViewById(R.id.sbGain)
        tvGain = findViewById(R.id.tvGain)
        cbNs = findViewById(R.id.cbNs)
        cbTts = findViewById(R.id.cbTts)

        btnStart.setOnClickListener { if (audio?.isRunning() == true) stopListening() else startListening() }
        btnDownload.setOnClickListener { downloadTranslator() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            panelSettings.visibility = if (panelSettings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            history.clear(); tvHistory.text = ""; tvZhLive.text = ""; tvPtLive.text = ""
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("traducao", history.toString()))
            Toast.makeText(this, "Histórico copiado", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Ajustes do microfone ----------

    private fun setupSettings() {
        spSource.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sources.map { it.label })
        spSource.setSelection(prefs.getInt("source", 0).coerceIn(0, sources.lastIndex))
        spSource.onItemSelectedListener = SimpleSelect { prefs.edit().putInt("source", it).apply(); restartIfRunning() }

        refreshDevices()
        spDevice.onItemSelectedListener = SimpleSelect { restartIfRunning() }
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshDevices()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshDevices()
        }, ui)

        sbGain.progress = prefs.getInt("gain", 5)
        showGain()
        sbGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                showGain(); audio?.gain = gainValue()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { prefs.edit().putInt("gain", s.progress).apply() }
        })

        cbNs.isChecked = prefs.getBoolean("ns", false)
        cbNs.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("ns", c).apply(); restartIfRunning() }
        cbTts.isChecked = prefs.getBoolean("tts", false)
        cbTts.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean("tts", c).apply()
            if (c) Toast.makeText(this, "Use fones de ouvido para o celular não ouvir a própria voz", Toast.LENGTH_LONG).show()
        }
    }

    private fun gainValue() = 1f + sbGain.progress / 10f
    private fun showGain() { tvGain.text = String.format(Locale.US, "Ganho digital: %.1fx (aumente para quem fala longe)", gainValue()) }

    private fun refreshDevices() {
        val wanted = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        val list = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in wanted }
        val keep = (spDevice.selectedItemPosition.takeIf { it > 0 }?.let { devices.getOrNull(it)?.id })
        devices = listOf<AudioDeviceInfo?>(null) + list
        val labels = listOf("Automático") + list.map { deviceLabel(it) }
        spDevice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = devices.indexOfFirst { it?.id == keep }.takeIf { it > 0 } ?: 0
        spDevice.setSelection(idx)
    }

    private fun deviceLabel(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Mic interno"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Fone com fio"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "Mic USB-C"
            else -> "Entrada"
        }
        val addr = if (Build.VERSION.SDK_INT >= 28) d.address else ""
        val where = when {
            addr.contains("bottom", true) -> " (inferior)"
            addr.contains("back", true) -> " (traseiro)"
            addr.contains("top", true) -> " (superior)"
            addr.isNotBlank() -> " ($addr)"
            else -> ""
        }
        return type + where
    }

    private fun restartIfRunning() {
        if (audio?.isRunning() == true) { stopListening(); startListening() }
    }

    // ---------- Modelos ----------

    private fun loadVoiceModel() {
        StorageService.unpack(this, "model-cn", "model",
            { m: Model -> model = m; modelMsg = "Voz: mandarim pronto"; updateStatus() },
            { e -> modelMsg = "Modelo de voz ausente no APK (${e.message})"; updateStatus() })
    }

    private fun checkTranslator() {
        translator.checkInstalled { ok ->
            translatorReady = ok
            btnDownload.visibility = if (ok) View.GONE else View.VISIBLE
            updateStatus()
        }
    }

    private fun downloadTranslator() {
        btnDownload.isEnabled = false
        btnDownload.text = "Baixando pelo Wi-Fi… (~60 MB)"
        translator.downloadWifiOnly(
            onOk = {
                translatorReady = true
                btnDownload.visibility = View.GONE
                Toast.makeText(this, "Tradução offline instalada. Pode desligar a internet.", Toast.LENGTH_LONG).show()
                updateStatus()
            },
            onErr = {
                btnDownload.isEnabled = true
                btnDownload.setText(R.string.download)
                Toast.makeText(this, "Conecte-se a uma rede Wi-Fi e tente de novo.", Toast.LENGTH_LONG).show()
            })
    }

    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("pt", "BR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsReady = r >= TextToSpeech.LANG_AVAILABLE
                tts?.setSpeechRate(1.15f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { ttsSpeaking = true }
                    override fun onDone(id: String?) { ttsSpeaking = tts?.isSpeaking == true }
                    @Deprecated("") override fun onError(id: String?) { ttsSpeaking = false }
                })
            }
        }
    }

    private fun updateStatus() {
        val tr = if (translatorReady) "Tradução: offline ✓" else "Tradução: falta o pacote (baixar 1x no Wi-Fi)"
        val running = if (audio?.isRunning() == true) "  •  OUVINDO" else ""
        tvStatus.text = "$modelMsg  •  $tr$running"
    }

    // ---------- Captura ----------

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        val m = model ?: run { Toast.makeText(this, "Aguarde o modelo de voz carregar", Toast.LENGTH_SHORT).show(); return }
        val engine = AudioEngine(m, this)
        engine.gain = gainValue()
        engine.isMuted = { ttsSpeaking }
        audio = engine
        engine.start(
            sources[spSource.selectedItemPosition.coerceIn(0, sources.lastIndex)].source,
            devices.getOrNull(spDevice.selectedItemPosition),
            cbNs.isChecked,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnStart.setText(R.string.stop)
        btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#C62828"))
        ui.post(partialTicker)
        updateStatus()
    }

    private fun stopListening() {
        ui.removeCallbacks(partialTicker)
        audio?.stop()
        audio = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnStart.setText(R.string.start)
        btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32"))
        level.progress = 0
        updateStatus()
    }

    /** Traduz a frase em andamento a cada ~450 ms, para a legenda acompanhar a fala. */
    private val partialTicker = object : Runnable {
        override fun run() {
            if (translatorReady && !partialBusy && pendingPartial.isNotEmpty() && pendingPartial != lastTranslatedPartial) {
                val text = pendingPartial
                val seq = phraseSeq
                partialBusy = true
                translator.translate(text) { pt ->
                    partialBusy = false
                    if (seq == phraseSeq && pt != null) {
                        lastTranslatedPartial = text
                        tvPtLive.text = pt
                    }
                }
            }
            if (audio?.isRunning() == true) ui.postDelayed(this, 450)
        }
    }

    // ---------- Callbacks do AudioEngine (thread de áudio) ----------

    override fun onPartial(zh: String) = ui.post {
        tvZhLive.text = zh
        pendingPartial = zh
    }.let { }

    override fun onFinal(zh: String) = ui.post {
        phraseSeq++
        pendingPartial = ""; lastTranslatedPartial = ""
        tvZhLive.text = ""
        if (!translatorReady) {
            appendHistory(zh, "(instale o pacote de tradução)")
            tvPtLive.text = ""
        } else {
            translator.translate(zh) { pt ->
                val out = pt ?: "(falha na tradução)"
                appendHistory(zh, out)
                tvPtLive.text = ""
                if (pt != null && cbTts.isChecked && ttsReady) {
                    tts?.speak(pt, TextToSpeech.QUEUE_ADD, null, "u${utterId++}")
                }
            }
        }
    }.let { }

    override fun onLevel(level: Float) = ui.post { this.level.progress = (level * 100).toInt() }.let { }

    override fun onError(msg: String) = ui.post {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        stopListening()
    }.let { }

    override fun onSourceUsed(source: Int) = ui.post {
        val wanted = sources[spSource.selectedItemPosition].source
        if (source != wanted) Toast.makeText(this, "Modo não suportado neste aparelho; usando Reconhecimento de voz", Toast.LENGTH_SHORT).show()
    }.let { }

    private fun appendHistory(zh: String, pt: String) {
        if (history.length > 30000) history.delete(0, history.length - 20000)
        var start = history.length
        history.append(zh).append('\n')
        history.setSpan(ForegroundColorSpan(Color.parseColor("#6F8AA3")), start, history.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        history.setSpan(RelativeSizeSpan(0.8f), start, history.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        start = history.length
        history.append(pt).append("\n\n")
        history.setSpan(StyleSpan(Typeface.BOLD), start, history.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvHistory.text = history
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_MIC && res.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Sem permissão do microfone o app não funciona", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        stopListening()
        translator.close()
        tts?.shutdown()
        model?.close()
        super.onDestroy()
    }

    private class SimpleSelect(val onSel: (Int) -> Unit) : AdapterView.OnItemSelectedListener {
        private var first = true
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            if (first) { first = false; return } // ignora o disparo automático da criação
            onSel(pos)
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    companion object { private const val REQ_MIC = 1 }
}
