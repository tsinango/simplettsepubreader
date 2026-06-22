package com.example.bertvits2_infer_wrapper.impl

import android.content.Context
import android.util.Log
import com.example.bertvits2_infer_wrapper.interfaces.IBertVITS2FullInfer
import com.example.bertvits2_infer_wrapper.interfaces.IBertVITS2SimpleInfer
import com.example.textpreprocess.preprocess.LANGUAGE_EN
import com.example.textpreprocess.preprocess.LANGUAGE_JP
import com.example.textpreprocess.preprocess.LANGUAGE_MIX_ZH_EN
import com.example.textpreprocess.preprocess.LANGUAGE_ZH
import com.example.textpreprocess.mix.DetectedLanguage
import com.example.textpreprocess.mix.detectLanguage
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.TreeMap

class BertVITS2SimpleInferImpl(
    val context: Context,
    val modelRootPath: String = context.filesDir.absolutePath,
) : IBertVITS2SimpleInfer {
    private val bertVITS2FullInfer: IBertVITS2FullInfer by lazy {
        BertVITS2FullInferImpl(context, modelRootPath)
    }
    private val nameListCache: MutableMap<String, String> = TreeMap()
    private val speakerSampleRateCache: MutableMap<String, Int> = TreeMap()

    override suspend fun init(): Boolean {
        val bv2Dir = File(modelRootPath, "bv2_model")
        if (!bv2Dir.exists()) {
            Log.e("BertVITS2SimpleInferImpl", "Failed to locate bv2_model at $modelRootPath/bv2_model")
            return false
        }
        bv2Dir.walkTopDown().forEach {
            if (it.isFile && it.name == "config.json") {
                val configJson = it
                val gson = Gson()
                val root = gson.fromJson(configJson.readText(), JsonObject::class.java)
                val spk2idJson = root.getAsJsonObject("data").getAsJsonObject("spk2id")
                val sampleRate = root.getAsJsonObject("data").getAsJsonPrimitive("sampling_rate")
                val type = object : TypeToken<Map<String, Int>>() {}.type
                val spk2idMap: Map<String, Int> = gson.fromJson(spk2idJson, type)
                val lang = it.parentFile!!.name
                spk2idMap.forEach { (name, spkId) ->
                    nameListCache[name] = "${spkId}-${lang}"
                    speakerSampleRateCache[name] = sampleRate.asInt
                }
            }
        }
        bertVITS2FullInfer.initBertVITS2Loader()
        if (!bertVITS2FullInfer.initPreprocessor()) {
            Log.e("BertVITS2SimpleInferImpl", "Failed to init preprocessor")
            return false
        }
        return true
    }

    override fun getSpkNameList(): List<String> = nameListCache.keys.toList()

    override suspend fun infer(text: String, spkName: String): Pair<FloatArray?, Int>? {
        val declaredLanguage = getLanguageTypeFromSpkName(spkName)
        val actualLanguage = if (declaredLanguage == LANGUAGE_MIX_ZH_EN) {
            when (detectLanguage(text)) {
                DetectedLanguage.PURE_ZH -> LANGUAGE_ZH
                DetectedLanguage.PURE_EN -> LANGUAGE_EN
                DetectedLanguage.MIX_ZH_EN -> LANGUAGE_MIX_ZH_EN
            }
        } else {
            declaredLanguage
        }
        setInternalModelPath(spkName, actualLanguage)
        val preprocessResult = bertVITS2FullInfer.preprocess(text, language = actualLanguage)
            ?: return Log.e("BertVITS2SimpleInferImpl", "Preprocess failed for text: $text").let { null }
        if (preprocessResult.errorMsg?.isNotEmpty() == true) {
            Log.e("BertVITS2SimpleInferImpl", "Preprocess error: ${preprocessResult.errorMsg}")
            return null
        }
        val spkId = nameListCache[spkName]!!.split("-")[0].toInt()
        val arr = bertVITS2FullInfer.startAudioInfer(preprocessResult, spkId)
        val rate = speakerSampleRateCache[spkName]
            ?: throw IllegalArgumentException("Sample rate not found for spkName: $spkName")
        return Pair(arr, rate)
    }

    override fun setAudioLengthScale(length_scale: Float) {
        bertVITS2FullInfer.setAudioLengthScale(length_scale)
    }

    override fun release() {
        bertVITS2FullInfer.destroyBertVITS2Loader()
    }

    private fun setInternalModelPath(spkName: String, actualLanguage: Int = getLanguageTypeFromSpkName(spkName)) {
        val modelDir = nameListCache[spkName]!!.split("-")[1]
        val basePath = "$modelRootPath/bv2_model/$modelDir"
        val files = mutableMapOf<String, String>()
        File(basePath).walkTopDown().forEach {
            if (it.isFile) {
                when {
                    it.name.endsWith("_enc.mnn") -> files["enc"] = it.absolutePath
                    it.name.endsWith("_dec.mnn") -> files["dec"] = it.absolutePath
                    it.name.endsWith("_flow.mnn") -> files["flow"] = it.absolutePath
                    it.name.endsWith("_emb.mnn") -> files["emb"] = it.absolutePath
                    it.name.endsWith("_dp.mnn") -> files["dp"] = it.absolutePath
                    it.name.endsWith("_sdp.mnn") -> files["sdp"] = it.absolutePath
                }
            }
        }
        val bertModelPath = when (actualLanguage) {
            LANGUAGE_ZH -> "$modelRootPath/bert/zh/chinese-roberta-wwm-ext-large-distilled-fp16.mnn"
            LANGUAGE_EN -> "$modelRootPath/bert/en/deberta-v3-large-distilled.mnn"
            LANGUAGE_JP -> "$modelRootPath/bert/jp/deberta-v2-large-japanese-char-wwm-distilled.mnn"
            LANGUAGE_MIX_ZH_EN -> ""
            else -> throw IllegalArgumentException("Unsupported language type")
        }
        bertVITS2FullInfer.setBertVITS2ModelPath(
            enc_model_path = files["enc"] ?: "",
            dec_model_path = files["dec"] ?: "",
            sdp_model_path = files["sdp"] ?: "",
            dp_model_path = files["dp"] ?: "",
            emb_model_path = files["emb"] ?: "",
            flow_model_path = files["flow"] ?: "",
            bert_model_path = bertModelPath,
        )
    }

    private fun getLanguageTypeFromSpkName(spkName: String): Int = when {
        spkName.endsWith("_ZH") -> LANGUAGE_ZH
        spkName.endsWith("_EN") -> LANGUAGE_EN
        spkName.endsWith("_MIX") -> LANGUAGE_MIX_ZH_EN
        spkName.endsWith("_JP") -> LANGUAGE_JP
        else -> throw IllegalArgumentException("Unsupported spkName suffix: $spkName")
    }
}