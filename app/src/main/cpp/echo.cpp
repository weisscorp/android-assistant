#include <jni.h>
#include <speex/speex_echo.h>
#include <speex/speex_preprocess.h>
#include <array>

struct Echo {
    SpeexEchoState *echo;
    SpeexPreprocessState *noise;
    Echo() {
        echo = speex_echo_state_init(160, 8192);
        int rate = 16000;
        speex_echo_ctl(echo, SPEEX_ECHO_SET_SAMPLING_RATE, &rate);
        noise = speex_preprocess_state_init(160, rate);
        speex_preprocess_ctl(noise, SPEEX_PREPROCESS_SET_ECHO_STATE, echo);
        int suppress = -20, residual = -45, active = -12, enabled = 1, disabled = 0;
        speex_preprocess_ctl(noise, SPEEX_PREPROCESS_SET_DENOISE, &enabled);
        speex_preprocess_ctl(noise, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &suppress);
        speex_preprocess_ctl(noise, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS, &residual);
        speex_preprocess_ctl(noise, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE, &active);
        speex_preprocess_ctl(noise, SPEEX_PREPROCESS_SET_AGC, &disabled);
    }
    ~Echo() { speex_preprocess_state_destroy(noise); speex_echo_state_destroy(echo); }
};
extern "C" JNIEXPORT jlong JNICALL Java_local_voice_EchoProcessor_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new Echo());
}
extern "C" JNIEXPORT void JNICALL Java_local_voice_EchoProcessor_nativeFree(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Echo *>(handle);
}
extern "C" JNIEXPORT void JNICALL Java_local_voice_EchoProcessor_nativeProcess(JNIEnv *env, jobject, jlong handle, jshortArray input, jshortArray reference, jshortArray output) {
    if (!handle || env->GetArrayLength(input) != 160 || env->GetArrayLength(reference) != 160 || env->GetArrayLength(output) != 160) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Echo frame must contain 160 samples"); return;
    }
    std::array<spx_int16_t,160> mic{}, play{}, out{};
    env->GetShortArrayRegion(input, 0, 160, mic.data());
    env->GetShortArrayRegion(reference, 0, 160, play.data());
    auto *state = reinterpret_cast<Echo *>(handle);
    speex_echo_cancellation(state->echo, mic.data(), play.data(), out.data());
    speex_preprocess_run(state->noise, out.data());
    env->SetShortArrayRegion(output, 0, 160, out.data());
}
