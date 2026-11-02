package br.jorge.tradutorzh

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tradutor de alta qualidade: Tencent HY-MT1.5-1.8B (GGUF Q4_K_M) rodando com llama.cpp no celular.
 * Modelo especializado em tradução do chinês para 33 idiomas, incluindo português.
 */
class LlamaTranslator {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var handle = 0L
    private val pending = AtomicInteger(0)

    @Volatile var isLoaded = false
        private set

    /** Quantas traduções estão na fila (para não acumular atraso). */
    fun queueSize() = pending.get()

    fun load(file: File, onDone: (Boolean) -> Unit) {
        worker.execute {
            if (handle != 0L) { nativeFree(handle); handle = 0L }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            handle = try { nativeLoad(file.absolutePath, threads, 1024) } catch (e: Throwable) { 0L }
            isLoaded = handle != 0L
            main.post { onDone(isLoaded) }
        }
    }

    /**
     * @param zh frase em chinês
     * @param context frases anteriores (melhora coerência de pronomes e termos)
     */
    fun translate(zh: String, context: String, cb: (String?) -> Unit) {
        pending.incrementAndGet()
        worker.execute {
            val prompt = if (context.isBlank()) {
                "将以下文本翻译为$TARGET，注意只需要输出翻译后的结果，不要额外解释：\n\n$zh"
            } else {
                "$context\n参考上面的信息，把下面的文本翻译成$TARGET，注意不需要翻译上文，也不要额外解释：\n$zh"
            }
            val out = try {
                if (handle == 0L) null
                else String(nativeTranslate(handle, prompt.toByteArray(Charsets.UTF_8), 160), Charsets.UTF_8)
                    .trim().ifEmpty { null }
            } catch (e: Throwable) { null }
            pending.decrementAndGet()
            main.post { cb(out) }
        }
    }

    fun close() {
        worker.execute { if (handle != 0L) { nativeFree(handle); handle = 0L; isLoaded = false } }
        worker.shutdown()
    }

    private external fun nativeLoad(path: String, nThreads: Int, nCtx: Int): Long
    private external fun nativeTranslate(handle: Long, prompt: ByteArray, maxTokens: Int): ByteArray
    private external fun nativeFree(handle: Long)

    companion object {
        /** português do Brasil */
        const val TARGET = "巴西葡萄牙语"
        init { System.loadLibrary("hymt") }
    }
}
