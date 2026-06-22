package com.example.textpreprocess.preprocess

import android.content.Context
import android.util.Log
import com.example.cpptokenizer.CppTokenizerJNI
import com.example.textpreprocess.zh.BV2Preprocess
import com.example.textpreprocess.zh.ZHBV2Impl
import java.io.File

class BertVITS2PreprocessFactoryImpl(val context: Context, val modelRootPath: String = context.filesDir.absolutePath) : IBertVITS2Preprocess {
    private val bV2Preprocess by lazy { BV2Preprocess(
        jieba_dict =  "$modelRootPath/preprocess/zh/dict/jieba.dict.utf8",
        jieba_user = "$modelRootPath/preprocess/zh/dict/user.dict.utf8",
        jieba_hmm = "$modelRootPath/preprocess/zh/dict/hmm_model.utf8",
        jieba_idf = "$modelRootPath/preprocess/zh/dict/idf.utf8",
        jieba_stop = "$modelRootPath/preprocess/zh/dict/stop_words.utf8",
        opencpop_strict_path = "$modelRootPath/preprocess/zh/opencpop-strict.txt"
        )
    }
    private val tokenizer by lazy { CppTokenizerJNI().apply {
        initTokenizerFromBlobJson("$modelRootPath/bert/zh/tokenizer.json")
    }}
    private val zhPreprocess: IBertVITS2ProcessInternal by lazy {
        ZHBV2Impl(bV2Preprocess, tokenizer)
    }
    override suspend fun preprocess(
        text: String,
        language: Int
    ): PreprocessResult? {
        Log.i("BertVITS2PreprocessImplFactory", "start preprocess language $language")
        if (language != LANGUAGE_ZH) {
            Log.e("BertVITS2PreprocessImplFactory", "Only ZH language supported, requested: $language")
            return null
        }
        return zhPreprocess.preprocess(text)
    }
}