// NOLINTBEGIN
#include <jni.h>
#include <string>
#include <vector>

#include "whisper.h"

#include <android/log.h>

namespace {

constexpr const char* k_log_tag = "whisper_jni";

void loge(const char* msg) {
    __android_log_print(ANDROID_LOG_ERROR, k_log_tag, "%s", msg);
}

std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string out = chars ? chars : "";
    if (chars) {
        env->ReleaseStringUTFChars(jstr, chars);
    }
    return out;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ichi2_anki_speech_WhisperEngine_nativeInit(JNIEnv* env, jobject /*thiz*/, jstring model_path) {
    const std::string path = jstring_to_string(env, model_path);
    if (path.empty()) {
        loge("empty model path");
        return 0;
    }

    struct whisper_context_params cparams = whisper_context_default_params();
    // reduce memory usage a bit on mobile
    cparams.use_gpu = false;

    struct whisper_context* ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        loge("whisper_init_from_file failed");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ichi2_anki_speech_WhisperEngine_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ichi2_anki_speech_WhisperEngine_nativeTranscribePcm16(
        JNIEnv* env,
        jobject /*thiz*/,
        jlong handle,
        jshortArray pcm,
        jint sample_rate,
        jstring language
) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }
    if (pcm == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize n = env->GetArrayLength(pcm);
    if (n <= 0) {
        return env->NewStringUTF("");
    }

    std::vector<jshort> pcm_short(static_cast<size_t>(n));
    env->GetShortArrayRegion(pcm, 0, n, pcm_short.data());

    std::vector<float> pcm_float(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        pcm_float[static_cast<size_t>(i)] = static_cast<float>(pcm_short[static_cast<size_t>(i)]) / 32768.0f;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.max_tokens = 0;
    params.token_timestamps = false;

    const std::string lang = jstring_to_string(env, language);
    if (!lang.empty()) {
        params.language = lang.c_str();
    }

    // NOTE: whisper.cpp expects 16kHz PCM samples. If not, the output may be poor.
    (void) sample_rate;

    const int ret = whisper_full(ctx, params, pcm_float.data(), static_cast<int>(pcm_float.size()));
    if (ret != 0) {
        loge("whisper_full failed");
        return env->NewStringUTF("");
    }

    const int n_segments = whisper_full_n_segments(ctx);
    std::string out;
    out.reserve(256);
    for (int i = 0; i < n_segments; i++) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text && text[0] != '\0') {
            if (!out.empty()) {
                out += " ";
            }
            out += text;
        }
    }
    return env->NewStringUTF(out.c_str());
}
// NOLINTEND

