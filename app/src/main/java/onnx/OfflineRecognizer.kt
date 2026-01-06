package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineRecognizerResult(
    val text: String,
    val tokens: Array<*>,
    val timestamps: FloatArray,
    val lang: String,
    val emotion: String,
    val event: String,

    // valid only for TDT models
    val durations: FloatArray,
)

data class OfflineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
)

data class OfflineParaformerModelConfig(
    var model: String = "",
)

data class OfflineNemoEncDecCtcModelConfig(
    var model: String = "",
)

data class OfflineDolphinModelConfig(
    var model: String = "",
)

data class OfflineZipformerCtcModelConfig(
    var model: String = "",
)

data class OfflineWenetCtcModelConfig(
    var model: String = "",
)

data class OfflineWhisperModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "en", // Used with multilingual model
    var task: String = "transcribe", // transcribe or translate
    var tailPaddings: Int = 1000, // Padding added at the end of the samples
)

data class OfflineCanaryModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var srcLang: String = "en",
    var tgtLang: String = "en",
    var usePnc: Boolean = true,
)

data class OfflineFireRedAsrModelConfig(
    var encoder: String = "",
    var decoder: String = "",
)

data class OfflineMoonshineModelConfig(
    var preprocessor: String = "",
    var encoder: String = "",
    var uncachedDecoder: String = "",
    var cachedDecoder: String = "",
)

data class OfflineSenseVoiceModelConfig(
    var model: String = "",
    var language: String = "",
    var useInverseTextNormalization: Boolean = true,
)

/**
 * 主要模型类型：
 *
 * - Paraformer: 轻量级端到端语音识别模型
 * - Transducer: 基于转换器的语音识别模型
 * - Whisper: OpenAI的多语言语音识别模型
 * - Nemo: NVIDIA的语音识别模型
 * - SenseVoice: 多语言语音识别模型
 * - Zipformer: 高性能语音识别模型
 */
data class OfflineModelConfig(
    var transducer: OfflineTransducerModelConfig = OfflineTransducerModelConfig(),
    var paraformer: OfflineParaformerModelConfig = OfflineParaformerModelConfig(),
    var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    var fireRedAsr: OfflineFireRedAsrModelConfig = OfflineFireRedAsrModelConfig(),
    var moonshine: OfflineMoonshineModelConfig = OfflineMoonshineModelConfig(),
    var nemo: OfflineNemoEncDecCtcModelConfig = OfflineNemoEncDecCtcModelConfig(),
    var senseVoice: OfflineSenseVoiceModelConfig = OfflineSenseVoiceModelConfig(),
    var dolphin: OfflineDolphinModelConfig = OfflineDolphinModelConfig(),
    var zipformerCtc: OfflineZipformerCtcModelConfig = OfflineZipformerCtcModelConfig(),
    var wenetCtc: OfflineWenetCtcModelConfig = OfflineWenetCtcModelConfig(),
    var canary: OfflineCanaryModelConfig = OfflineCanaryModelConfig(),
    var teleSpeech: String = "",
    var numThreads: Int = 4,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var tokens: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    // var lmConfig: OfflineLMConfig(), // TODO(fangjun): enable it
    var hr: HomophoneReplacerConfig = HomophoneReplacerConfig(),
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

class OfflineRecognizer(
    assetManager: AssetManager? = null,
    val config: OfflineRecognizerConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun createStream(): OfflineStream {
        val p = createStream(ptr)
        return OfflineStream(p)
    }

    fun getResult(stream: OfflineStream): OfflineRecognizerResult {
        val objArray = getResult(stream.ptr)

        val text = objArray[0] as String
        val tokens = objArray[1] as Array<*>
        val timestamps = objArray[2] as FloatArray
        val lang = objArray[3] as String
        val emotion = objArray[4] as String
        val event = objArray[5] as String
        val durations = objArray[6] as FloatArray
        return OfflineRecognizerResult(
            text = text,
            tokens = tokens,
            timestamps = timestamps,
            lang = lang,
            emotion = emotion,
            event = event,
            durations = durations,
        )
    }

    fun decode(stream: OfflineStream) = decode(ptr, stream.ptr)

    fun setConfig(config: OfflineRecognizerConfig) = setConfig(ptr, config)

    private external fun delete(ptr: Long)

    private external fun createStream(ptr: Long): Long

    private external fun setConfig(ptr: Long, config: OfflineRecognizerConfig)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineRecognizerConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineRecognizerConfig,
    ): Long

    private external fun decode(ptr: Long, streamPtr: Long)

    private external fun getResult(streamPtr: Long): Array<Any>

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}

/*
Please see
https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
for a list of pre-trained models.

We only add a few here. Please change the following code
to add your own. (It should be straightforward to add a new model
by following the code)

@param type

0 - csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14 (Chinese)
    https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html#csukuangfj-sherpa-onnx-paraformer-zh-2023-09-14-chinese
    int8

1 - icefall-asr-multidataset-pruned_transducer_stateless7-2023-05-04 (English)
    https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/zipformer-transducer-models.html#icefall-asr-multidataset-pruned-transducer-stateless7-2023-05-04-english
    encoder int8, decoder/joiner float32

2 - sherpa-onnx-whisper-tiny.en
    https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/tiny.en.html#tiny-en
    encoder int8, decoder int8

3 - sherpa-onnx-whisper-base.en
    https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/tiny.en.html#tiny-en
    encoder int8, decoder int8

4 - pkufool/icefall-asr-zipformer-wenetspeech-20230615 (Chinese)
    https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/zipformer-transducer-models.html#pkufool-icefall-asr-zipformer-wenetspeech-20230615-chinese
    encoder/joiner int8, decoder fp32

 */
/**
 * 获取离线模型配置
 * @param type 模型类型ID
 * @return 离线模型配置对象，根据type参数返回不同的模型配置
 */
fun getOfflineModelConfig(type: Int): OfflineModelConfig? {
    when (type) {
        // 0: 中文Paraformer模型
        0 -> {
            val modelDir = "sherpa-onnx-paraformer-zh-2023-09-14"
            return OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "paraformer",
            )
        }

        // 1: 多语言Transducer模型
        1 -> {
            val modelDir = "icefall-asr-multidataset-pruned_transducer_stateless7-2023-05-04"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-30-avg-4.int8.onnx",
                    decoder = "$modelDir/decoder-epoch-30-avg-4.onnx",
                    joiner = "$modelDir/joiner-epoch-30-avg-4.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "transducer",
            )
        }

        // 2: 英语Whisper Tiny模型
        2 -> {
            val modelDir = "sherpa-onnx-whisper-tiny.en"
            return OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$modelDir/tiny.en-encoder.int8.onnx",
                    decoder = "$modelDir/tiny.en-decoder.int8.onnx",
                ),
                tokens = "$modelDir/tiny.en-tokens.txt",
                modelType = "whisper",
            )
        }

        // 3: 英语Whisper Base模型
        3 -> {
            val modelDir = "sherpa-onnx-whisper-base.en"
            return OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$modelDir/base.en-encoder.int8.onnx",
                    decoder = "$modelDir/base.en-decoder.int8.onnx",
                ),
                tokens = "$modelDir/base.en-tokens.txt",
                modelType = "whisper",
            )
        }

        // 4: 中文Zipformer模型 (WenetSpeech数据集)
        4 -> {
            val modelDir = "icefall-asr-zipformer-wenetspeech-20230615"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-12-avg-4.int8.onnx",
                    decoder = "$modelDir/decoder-epoch-12-avg-4.onnx",
                    joiner = "$modelDir/joiner-epoch-12-avg-4.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "transducer",
            )
        }

        // 5: 中文（简体）多语言Zipformer模型
        5 -> {
            val modelDir = "sherpa-onnx-zipformer-multi-zh-hans-2023-9-2"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-20-avg-1.int8.onnx",
                    decoder = "$modelDir/decoder-epoch-20-avg-1.onnx",
                    joiner = "$modelDir/joiner-epoch-20-avg-1.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "transducer",
            )
        }

        // 6: 英语Nemo CTC Citrinet-512模型
        6 -> {
            val modelDir = "sherpa-onnx-nemo-ctc-en-citrinet-512"
            return OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 7: 多语言Nemo Fast Conformer CTC模型 (支持：比利时语、德语、英语、西班牙语、法语、克罗地亚语、意大利语、波兰语、俄语、乌克兰语)
        7 -> {
            val modelDir = "sherpa-onnx-nemo-fast-conformer-ctc-be-de-en-es-fr-hr-it-pl-ru-uk-20k"
            return OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 8: 英语Nemo Fast Conformer CTC模型 (24500词汇量)
        8 -> {
            val modelDir = "sherpa-onnx-nemo-fast-conformer-ctc-en-24500"
            return OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 9: 多语言Nemo Fast Conformer CTC模型 (支持：英语、德语、西班牙语、法语)
        9 -> {
            val modelDir = "sherpa-onnx-nemo-fast-conformer-ctc-en-de-es-fr-14288"
            return OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 10: 西班牙语Nemo Fast Conformer CTC模型
        10 -> {
            val modelDir = "sherpa-onnx-nemo-fast-conformer-ctc-es-1424"
            return OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 11: 中文Telespeech CTC模型
        11 -> {
            val modelDir = "sherpa-onnx-telespeech-ctc-int8-zh-2024-06-04"
            return OfflineModelConfig(
                teleSpeech = "$modelDir/model.int8.onnx",
                tokens = "$modelDir/tokens.txt",
                modelType = "telespeech_ctc",
            )
        }

        // 12: 泰语Zipformer模型
        12 -> {
            val modelDir = "sherpa-onnx-zipformer-thai-2024-06-20"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-12-avg-5.int8.onnx",
                    decoder = "$modelDir/decoder-epoch-12-avg-5.onnx",
                    joiner = "$modelDir/joiner-epoch-12-avg-5.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "transducer",
            )
        }

        // 13: 韩语Zipformer模型
        13 -> {
            val modelDir = "sherpa-onnx-zipformer-korean-2024-06-24"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$modelDir/decoder-epoch-99-avg-1.onnx",
                    joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "transducer",
            )
        }

        // 14: 中文小型Paraformer模型
        14 -> {
            val modelDir = "sherpa-onnx-paraformer-zh-small-2024-03-09"
            return OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "paraformer",
            )
        }

        // 15: 多语言SenseVoice模型 (支持：中文、英语、日语、韩语、粤语)
        15 -> {
            val modelDir = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
            return OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 16: 日语Zipformer ReazonSpeech模型
        16 -> {
            val modelDir = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$modelDir/decoder-epoch-99-avg-1.onnx",
                    joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "transducer",
            )
        }

        // 17: 俄语Zipformer模型
        17 -> {
            val modelDir = "sherpa-onnx-zipformer-ru-2024-09-18"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.onnx",
                    joiner = "$modelDir/joiner.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "transducer",
            )
        }

        // 18: 俄语小型Zipformer模型
        18 -> {
            val modelDir = "sherpa-onnx-small-zipformer-ru-2024-09-18"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.onnx",
                    joiner = "$modelDir/joiner.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "transducer",
            )
        }

        // 19: 俄语Nemo CTC Giga模型
        19 -> {
            val modelDir = "sherpa-onnx-nemo-ctc-giga-am-russian-2024-10-24"
            return OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 20: 俄语Nemo Transducer Giga模型
        20 -> {
            val modelDir = "sherpa-onnx-nemo-transducer-giga-am-russian-2024-10-24"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.onnx",
                    joiner = "$modelDir/joiner.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "nemo_transducer",
            )
        }

        // 21: 英语Moonshine Tiny模型
        21 -> {
            val modelDir = "sherpa-onnx-moonshine-tiny-en-int8"
            return OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = "$modelDir/preprocess.onnx",
                    encoder = "$modelDir/encode.int8.onnx",
                    uncachedDecoder = "$modelDir/uncached_decode.int8.onnx",
                    cachedDecoder = "$modelDir/cached_decode.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 22: 英语Moonshine Base模型
        22 -> {
            val modelDir = "sherpa-onnx-moonshine-base-en-int8"
            return OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = "$modelDir/preprocess.onnx",
                    encoder = "$modelDir/encode.int8.onnx",
                    uncachedDecoder = "$modelDir/uncached_decode.int8.onnx",
                    cachedDecoder = "$modelDir/cached_decode.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 23: 中文-英语混合Zipformer模型
        23 -> {
            val modelDir = "sherpa-onnx-zipformer-zh-en-2023-11-22"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-34-avg-19.int8.onnx",
                    decoder = "$modelDir/decoder-epoch-34-avg-19.onnx",
                    joiner = "$modelDir/joiner-epoch-34-avg-19.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "transducer",
            )
        }

        // 24: 中文-英语混合Fire Red ASR大型模型
        24 -> {
            val modelDir = "sherpa-onnx-fire-red-asr-large-zh_en-2025-02-16"
            return OfflineModelConfig(
                fireRedAsr = OfflineFireRedAsrModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 25: 多语言Dolphin Base CTC模型
        25 -> {
            val modelDir = "sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02"
            return OfflineModelConfig(
                dolphin = OfflineDolphinModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 26: 越南语Zipformer模型
        26 -> {
            val modelDir = "sherpa-onnx-zipformer-vi-int8-2025-04-20"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-12-avg-8.int8.onnx",
                    decoder = "$modelDir/decoder-epoch-12-avg-8.onnx",
                    joiner = "$modelDir/joiner-epoch-12-avg-8.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "transducer",
            )
        }

        // 27: 俄语Nemo CTC Giga V2模型
        27 -> {
            val modelDir = "sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19"
            return OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 28: 俄语Nemo Transducer Giga V2模型
        28 -> {
            val modelDir = "sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.onnx",
                    joiner = "$modelDir/joiner.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "nemo_transducer",
            )
        }

        // 29: 俄语Zipformer INT8模型
        29 -> {
            val modelDir = "sherpa-onnx-zipformer-ru-int8-2025-04-20"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.onnx",
                    joiner = "$modelDir/joiner.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "transducer",
            )
        }

        // 30: Nemo Parakeet TDT 0.6B V2模型
        30 -> {
            val modelDir = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.int8.onnx",
                    joiner = "$modelDir/joiner.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "nemo_transducer",
            )
        }

        // 31: 中文Zipformer CTC INT8模型
        31 -> {
            val modelDir = "sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03"
            return OfflineModelConfig(
                zipformerCtc = OfflineZipformerCtcModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 32: 多语言Nemo Canary模型 (支持：英语、西班牙语、德语、法语)
        32 -> {
            val modelDir = "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8"
            return OfflineModelConfig(
                canary = OfflineCanaryModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.int8.onnx",
                    srcLang = "en",
                    tgtLang = "en",
                    usePnc = true,
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 33: 英语Nemo Parakeet TDT CTC 110M模型
        33 -> {
            val modelDir = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
            return OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 34: 日语Nemo Parakeet TDT CTC 0.6B模型
        34 -> {
            val modelDir = "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8"
            return OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 35: 葡萄牙语Nemo Transducer模型
        35 -> {
            val modelDir = "sherpa-onnx-nemo-transducer-stt_pt_fastconformer_hybrid_large_pc-int8"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.int8.onnx",
                    joiner = "$modelDir/joiner.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "nemo_transducer",
            )
        }

        // 36: 葡萄牙语Nemo模型
        36 -> {
            val modelDir = "sherpa-onnx-nemo-stt_pt_fastconformer_hybrid_large_pc-int8"
            return OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 37: 德语Nemo Transducer模型
        37 -> {
            val modelDir = "sherpa-onnx-nemo-transducer-stt_de_fastconformer_hybrid_large_pc-int8"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.int8.onnx",
                    joiner = "$modelDir/joiner.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "nemo_transducer",
            )
        }

        // 38: 德语Nemo模型
        38 -> {
            val modelDir = "sherpa-onnx-nemo-stt_de_fastconformer_hybrid_large_pc-int8"
            return OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 39: 中文小型Zipformer CTC INT8模型
        39 -> {
            val modelDir = "sherpa-onnx-zipformer-ctc-small-zh-int8-2025-07-16"
            return OfflineModelConfig(
                zipformerCtc = OfflineZipformerCtcModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 40: Nemo Parakeet TDT 0.6B V3模型
        40 -> {
            val modelDir = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
            return OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.int8.onnx",
                    joiner = "$modelDir/joiner.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "nemo_transducer",
            )
        }

        // 41: 多语言SenseVoice INT8模型 (支持：中文、英语、日语、韩语、粤语)
        41 -> {
            val modelDir = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"
            return OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 42: 多语言WenetSpeech Yue模型 (支持：中文、英语、粤语)
        42 -> {
            val modelDir = "sherpa-onnx-wenetspeech-yue-u2pp-conformer-ctc-zh-en-cantonese-int8-2025-09-10"
            return OfflineModelConfig(
                wenetCtc = OfflineWenetCtcModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
            )
        }

        // 43: 中文Paraformer INT8模型 (2025-10-07更新)
        43 -> {
            val modelDir = "sherpa-onnx-paraformer-zh-int8-2025-10-07"
            return OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                modelType = "paraformer",
            )
        }
    }
    return null
}
