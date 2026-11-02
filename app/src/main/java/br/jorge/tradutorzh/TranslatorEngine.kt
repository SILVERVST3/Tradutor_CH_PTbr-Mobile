package br.jorge.tradutorzh

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/** Tradução chinês -> português 100% no aparelho (ML Kit). */
class TranslatorEngine {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.PORTUGUESE)
            .build()
    )
    private val manager = RemoteModelManager.getInstance()
    private val zh = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
    private val pt = TranslateRemoteModel.Builder(TranslateLanguage.PORTUGUESE).build()

    /** Verifica se os pacotes já estão no celular (não usa internet). */
    fun checkInstalled(cb: (Boolean) -> Unit) {
        manager.isModelDownloaded(zh).addOnCompleteListener { t1 ->
            val okZh = t1.isSuccessful && t1.result == true
            manager.isModelDownloaded(pt).addOnCompleteListener { t2 ->
                cb(okZh && t2.isSuccessful && t2.result == true)
            }
        }
    }

    /** Baixa os pacotes apenas via Wi-Fi — nunca por dados móveis. */
    fun downloadWifiOnly(onOk: () -> Unit, onErr: (Exception) -> Unit) {
        val cond = DownloadConditions.Builder().requireWifi().build()
        translator.downloadModelIfNeeded(cond)
            .addOnSuccessListener { onOk() }
            .addOnFailureListener { onErr(it) }
    }

    fun translate(text: String, cb: (String?) -> Unit) {
        translator.translate(text)
            .addOnSuccessListener { cb(it) }
            .addOnFailureListener { cb(null) }
    }

    fun close() = translator.close()
}
