package br.jorge.tradutorzh

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), AsrEngine.Listener {

    // ---------- views ----------
    private lateinit var tvStatus: TextView
    private lateinit var tvZhLive: TextView
    private lateinit var tvPtLive: TextView
    private lateinit var tvHistory: TextView
    private lateinit var scroll: ScrollView
    private lateinit var level: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var panelSetup: View
    private lateinit var btnDownload: Button
    private lateinit var rowHy: View
    private lateinit var btnDownloadHy: Button
    private lateinit var hyProgress: ProgressBar
    private lateinit var tvSetup: TextView
    private lateinit var panelSettings: View
    private lateinit var spSource: Spinner
    private lateinit var spDevice: Spinner
    private lateinit var sbGain: SeekBar
    private lateinit var tvGain: TextView
    private lateinit var cbNs: CheckBox
    private lateinit var cbTts: CheckBox
    private lateinit var cbHy: CheckBox

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // ---------- motores ----------
    private var asr: AsrEngine? = null
    private var asrMsg = "Voz: carregando modelos…"
    private var listening = false
    private val mlkit = TranslatorEngine()
    private var mlkitReady = false
    private val hy = LlamaTranslator()
    private val downloader by lazy { ModelDownloader(this) }
    private var hyState = "HY-MT: não instalado"
    private var downloading = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var ttsSpeaking = false
    private var utterId = 0

    // ---------- legenda ao vivo ----------
    private var pendingPartial = ""
    private var lastTranslatedPartial = ""
    private var partialBusy = false
    private var phraseSeq = 0

    // ---------- histórico ----------
    private class Entry(val zh: String) {
        var ml: String? = null      // tradução rápida (ML Kit)
        var hy: String? = null      // tradução final (HY-MT)
        var hyPending = false
        var spoken = false
        val best get() = hy ?: ml
    }
    private val entries = ArrayList<Entry>()

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

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        loadAsr()
        checkMlKit()
        loadHyIfInstalled()
        initTts()
        refreshSetup()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvZhLive = findViewById(R.id.tvZhLive)
        tvPtLive = findViewById(R.id.tvPtLive)
        tvHistory = findViewById(R.id.tvHistory)
        scroll = findViewById(R.id.scroll)
        level = findViewById(R.id.level)
        btnStart = findViewById(R.id.btnStart)
        panelSetup = findViewById(R.id.panelSetup)
        btnDownload = findViewById(R.id.btnDownload)
        rowHy = findViewById(R.id.rowHy)
        btnDownloadHy = findViewById(R.id.btnDownloadHy)
        hyProgress = findViewById(R.id.hyProgress)
        tvSetup = findViewById(R.id.tvSetup)
        panelSettings = findViewById(R.id.panelSettings)
        spSource = findViewById(R.id.spSource)
        spDevice = findViewById(R.id.spDevice)
        sbGain = findViewById(R.id.sbGain)
        tvGain = findViewById(R.id.tvGain)
        cbNs = findViewById(R.id.cbNs)
        cbTts = findViewById(R.id.cbTts)
        cbHy = findViewById(R.id.cbHy)

        btnStart.setOnClickListener { if (listening) stopListening() else startListening() }
        btnDownload.setOnClickListener { downloadMlKit() }
        btnDownloadHy.setOnClickListener { if (downloading) downloader.cancel() else downloadHy() }
        findViewById<Button>(R.id.btnImportHy).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            startActivityForResult(i, REQ_IMPORT)
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            panelSettings.visibility = if (panelSettings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            entries.clear(); renderHistory(); tvZhLive.text = ""; tvPtLive.text = ""
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val txt = entries.joinToString("\n\n") { "${it.zh}\n${it.best ?: ""}" }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("traducao", txt))
            Toast.makeText(this, "Histórico copiado", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- ajustes ----------

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
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { showGain(); asr?.gain = gainValue() }
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
        cbHy.isChecked = prefs.getBoolean("hy", true)
        cbHy.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("hy", c).apply() }
    }

    private fun gainValue() = 1f + sbGain.progress / 10f
    private fun showGain() {
        tvGain.text = String.format(Locale.US, "Ganho digital: %.1fx (aumente para quem fala longe)", gainValue())
    }

    private fun refreshDevices() {
        val wanted = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        val list = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in wanted }
        val keepId = devices.getOrNull(spDevice.selectedItemPosition)?.id
        devices = listOf<AudioDeviceInfo?>(null) + list
        spDevice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Automático") + list.map { deviceLabel(it) })
        spDevice.setSelection(devices.indexOfFirst { it != null && it.id == keepId }.takeIf { it > 0 } ?: 0)
    }

    private fun deviceLabel(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Mic interno"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Fone com fio"
            else -> "Mic USB-C"
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

    private fun restartIfRunning() { if (listening) { stopListening(); startListening() } }

    // ---------- modelos ----------

    private fun loadAsr() {
        Thread({
            try {
                val e = AsrEngine(assets)
                ui.post { asr = e; asrMsg = "Voz: mandarim pronto"; updateStatus() }
            } catch (t: Throwable) {
                ui.post { asrMsg = "Voz: erro ao carregar (${t.message})"; updateStatus() }
            }
        }, "asr-load").start()
    }

    private fun checkMlKit() {
        mlkit.checkInstalled { ok -> mlkitReady = ok; refreshSetup() }
    }

    private fun downloadMlKit() {
        btnDownload.isEnabled = false
        btnDownload.text = "Baixando pelo Wi-Fi…"
        mlkit.downloadWifiOnly(
            onOk = { mlkitReady = true; refreshSetup() },
            onErr = {
                btnDownload.isEnabled = true
                btnDownload.setText(R.string.download)
                Toast.makeText(this, "Conecte-se a uma rede Wi-Fi e tente de novo.", Toast.LENGTH_LONG).show()
            })
    }

    private fun loadHyIfInstalled() {
        if (!downloader.isInstalled()) { hyState = "HY-MT: não instalado"; refreshSetup(); return }
        hyState = "HY-MT: carregando…"
        updateStatus()
        hy.load(downloader.modelFile) { ok ->
            hyState = if (ok) "HY-MT: pronto" else "HY-MT: falha ao carregar"
            refreshSetup()
        }
    }

    private fun downloadHy() {
        if (!downloader.isOnWifi()) {
            Toast.makeText(this, "Sem Wi-Fi. O download nunca usa dados móveis.", Toast.LENGTH_LONG).show()
            return
        }
        downloading = true
        btnDownloadHy.text = "Cancelar download"
        hyProgress.visibility = View.VISIBLE
        downloader.download(
            onProgress = { pct, bytes ->
                hyProgress.progress = pct
                tvSetup.text = "Baixando HY-MT: $pct% (${bytes / (1024 * 1024)} MB)"
            },
            onDone = { ok, msg ->
                downloading = false
                hyProgress.visibility = View.GONE
                btnDownloadHy.setText(R.string.download_hy)
                if (ok) loadHyIfInstalled()
                else { tvSetup.text = "Download interrompido: $msg"; refreshSetup() }
            })
    }

    @Deprecated("Activity simples, sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && uri != null) {
            tvSetup.text = "Importando modelo…"
            downloader.importFrom(uri) { ok ->
                if (ok) loadHyIfInstalled()
                else Toast.makeText(this, "Arquivo inválido: escolha o HY-MT1.5-1.8B-Q4_K_M.gguf", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshSetup() {
        btnDownload.visibility = if (mlkitReady) View.GONE else View.VISIBLE
        val hyMissing = !downloader.isInstalled()
        rowHy.visibility = if (hyMissing) View.VISIBLE else View.GONE
        panelSetup.visibility = if (!mlkitReady || hyMissing || downloading) View.VISIBLE else View.GONE
        if (!downloading) tvSetup.text = when {
            !mlkitReady && hyMissing -> "Instalação única no Wi-Fi. Depois tudo funciona sem internet."
            hyMissing -> "Opcional: o HY-MT dá a melhor tradução (frase final)."
            !mlkitReady -> "Opcional: legenda instantânea enquanto a pessoa fala."
            else -> ""
        }
        updateStatus()
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
        val ml = if (mlkitReady) "Legenda: ✓" else "Legenda: falta pacote"
        val on = if (listening) "  •  OUVINDO" else ""
        tvStatus.text = "$asrMsg  •  $ml  •  $hyState$on"
    }

    // ---------- captura ----------

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        val e = asr ?: run { Toast.makeText(this, "Aguarde os modelos de voz carregarem", Toast.LENGTH_SHORT).show(); return }
        if (!mlkitReady && !hy.isLoaded) {
            Toast.makeText(this, "Instale ao menos um pacote de tradução (Wi-Fi, uma vez)", Toast.LENGTH_LONG).show()
        }
        e.gain = gainValue()
        e.isMuted = { ttsSpeaking }
        e.start(
            sources[spSource.selectedItemPosition.coerceIn(0, sources.lastIndex)].source,
            devices.getOrNull(spDevice.selectedItemPosition),
            cbNs.isChecked,
            this,
        )
        listening = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnStart.setText(R.string.stop)
        btnStart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
        ui.post(partialTicker)
        updateStatus()
    }

    private fun stopListening() {
        listening = false
        ui.removeCallbacks(partialTicker)
        asr?.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnStart.setText(R.string.start)
        btnStart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
        level.progress = 0
        updateStatus()
    }

    /** Traduz a frase em andamento a cada ~450 ms (ML Kit, instantâneo) para a legenda acompanhar a fala. */
    private val partialTicker = object : Runnable {
        override fun run() {
            if (mlkitReady && !partialBusy && pendingPartial.isNotEmpty() && pendingPartial != lastTranslatedPartial) {
                val text = pendingPartial
                val seq = phraseSeq
                partialBusy = true
                mlkit.translate(text) { pt ->
                    partialBusy = false
                    if (seq == phraseSeq && pt != null) { lastTranslatedPartial = text; tvPtLive.text = pt }
                }
            }
            if (listening) ui.postDelayed(this, 450)
        }
    }

    // ---------- callbacks do reconhecedor (outras threads) ----------

    override fun onPartial(zh: String) { ui.post { tvZhLive.text = zh; pendingPartial = zh } }

    override fun onFinal(zh: String) { ui.post { handleFinal(zh) } }

    override fun onLevel(level: Float) { ui.post { this.level.progress = (level * 100).toInt() } }

    override fun onError(msg: String) { ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); stopListening() } }

    override fun onSourceUsed(source: Int) {
        ui.post {
            if (source != sources[spSource.selectedItemPosition].source)
                Toast.makeText(this, "Modo não suportado; usando Reconhecimento de voz", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleFinal(zh: String) {
        phraseSeq++
        pendingPartial = ""; lastTranslatedPartial = ""
        tvZhLive.text = ""; tvPtLive.text = ""

        val e = Entry(zh)
        // contexto: as duas frases anteriores, para o HY-MT manter coerência
        val context = entries.takeLast(2).joinToString("") { it.zh }
        entries.add(e)
        if (entries.size > 300) entries.subList(0, entries.size - 300).clear()

        // fila longa = orador rápido demais: a frase fica só com a tradução rápida
        if (cbHy.isChecked && hy.isLoaded && hy.queueSize() < 3) {
            e.hyPending = true
            hy.translate(zh, context) { pt ->
                e.hyPending = false
                if (pt != null) e.hy = pt
                onEntryChanged(e)
            }
        }
        if (mlkitReady) mlkit.translate(zh) { pt -> e.ml = pt ?: "(falha na tradução)"; onEntryChanged(e) }
        onEntryChanged(e)
    }

    private fun onEntryChanged(e: Entry) {
        renderHistory()
        val text = e.best
        if (!e.spoken && !e.hyPending && text != null) {
            e.spoken = true
            if (cbTts.isChecked && ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "u${utterId++}")
        }
    }

    private fun renderHistory() {
        val sb = SpannableStringBuilder()
        for (e in entries) {
            var s = sb.length
            sb.append(e.zh).append('\n')
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#6F8AA3")), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.8f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s = sb.length
            when {
                e.hy != null -> {
                    sb.append(e.hy)
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                e.ml != null -> {
                    sb.append(if (e.hyPending) "≈ ${e.ml}" else e.ml)
                    if (e.hyPending) sb.setSpan(ForegroundColorSpan(Color.parseColor("#B0BEC5")), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    else sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                e.hyPending -> {
                    sb.append("traduzindo…")
                    sb.setSpan(ForegroundColorSpan(Color.parseColor("#78909C")), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> sb.append("(sem pacote de tradução instalado)")
            }
            sb.append("\n\n")
        }
        val atBottom = !scroll.canScrollVertically(1)
        tvHistory.text = sb
        if (atBottom || listening) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_MIC && res.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Sem permissão do microfone o app não funciona", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        stopListening()
        downloader.cancel()
        asr?.release()
        mlkit.close()
        hy.close()
        tts?.shutdown()
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

    companion object {
        private const val REQ_MIC = 1
        private const val REQ_IMPORT = 2
    }
}
