package br.jorge.tradutorzh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Baixa o modelo de tradução HY-MT (~1,1 GB) SOMENTE por Wi-Fi, com retomada se cair.
 * Também permite importar o .gguf copiado manualmente para o celular.
 */
class ModelDownloader(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancel = false

    val modelFile: File get() = File(ctx.filesDir, "hy-mt1.5-1.8b-q4_k_m.gguf")
    private val partFile: File get() = File(ctx.filesDir, "hy-mt.part")

    fun isInstalled(): Boolean = modelFile.exists() && modelFile.length() > MIN_SIZE && hasGgufMagic(modelFile)

    fun isOnWifi(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return wifi && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun cancel() { cancel = true }

    fun download(onProgress: (Int, Long) -> Unit, onDone: (Boolean, String) -> Unit) {
        cancel = false
        Thread({
            var ok = false
            var msg = ""
            for (url in URLS) {
                if (cancel) break
                try {
                    downloadFrom(url, onProgress)
                    if (partFile.length() > MIN_SIZE && hasGgufMagic(partFile)) {
                        modelFile.delete()
                        ok = partFile.renameTo(modelFile)
                        if (ok) break
                    } else {
                        partFile.delete(); msg = "arquivo inválido"
                    }
                } catch (e: WifiLost) {
                    msg = "Wi-Fi desconectado — o download continua de onde parou ao tentar de novo"
                    break
                } catch (e: Exception) {
                    msg = e.message ?: e.javaClass.simpleName
                }
            }
            if (cancel) msg = "cancelado"
            main.post { onDone(ok, msg) }
        }, "hy-download").start()
    }

    private class WifiLost : Exception()

    private fun downloadFrom(url: String, onProgress: (Int, Long) -> Unit) {
        // só retoma um download parcial que veio do mesmo endereço
        val src = File(ctx.filesDir, "hy-mt.src")
        if (!src.exists() || src.readText() != url) { partFile.delete(); src.writeText(url) }
        var done = if (partFile.exists()) partFile.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20000
        c.readTimeout = 30000
        if (done > 0) c.setRequestProperty("Range", "bytes=$done-")
        c.connect()
        val code = c.responseCode
        if (code == 200) done = 0L // servidor ignorou o Range: recomeça
        if (code != 200 && code != 206) throw Exception("HTTP $code")
        val total = done + c.contentLengthLong.coerceAtLeast(0)

        RandomAccessFile(partFile, "rw").use { raf ->
            raf.setLength(done)
            raf.seek(done)
            c.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var lastUi = 0L
                var lastNetCheck = 0L
                while (!cancel) {
                    val r = input.read(buf)
                    if (r < 0) break
                    raf.write(buf, 0, r)
                    done += r
                    val now = System.currentTimeMillis()
                    if (now - lastNetCheck > 3000) {
                        lastNetCheck = now
                        if (!isOnWifi()) throw WifiLost()
                    }
                    if (now - lastUi > 400) {
                        lastUi = now
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        main.post { onProgress(pct, done) }
                    }
                }
            }
        }
        c.disconnect()
    }

    /** Copia um .gguf escolhido pelo usuário (ex.: baixado no PC e passado por cabo). */
    fun importFrom(uri: Uri, onDone: (Boolean) -> Unit) {
        Thread({
            val ok = try {
                ctx.contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(partFile).use { input.copyTo(it, 1 shl 20) }
                }
                if (partFile.length() > MIN_SIZE && hasGgufMagic(partFile)) {
                    modelFile.delete(); partFile.renameTo(modelFile)
                } else { partFile.delete(); false }
            } catch (e: Exception) { false }
            main.post { onDone(ok) }
        }, "hy-import").start()
    }

    private fun hasGgufMagic(f: File): Boolean = try {
        f.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF" }
    } catch (e: Exception) { false }

    companion object {
        private const val MIN_SIZE = 500L * 1024 * 1024
        val URLS = listOf(
            "https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF/resolve/main/HY-MT1.5-1.8B-Q4_K_M.gguf",
            "https://huggingface.co/mradermacher/HY-MT1.5-1.8B-GGUF/resolve/main/HY-MT1.5-1.8B.Q4_K_M.gguf",
        )
    }
}
