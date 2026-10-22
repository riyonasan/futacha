#include "FutachaTracking.h"
#include <jni.h>
#include <cstdint>
#include "FutachaOrb.h"

namespace {
FutachaTracker* tracker(jlong pointer) {
    return reinterpret_cast<FutachaTracker*>(static_cast<intptr_t>(pointer));
}
bool validFrame(JNIEnv* env, jbyteArray gray, jint width, jint height) {
    const auto count = static_cast<int64_t>(width) * height;
    return gray && width > 0 && height > 0 && count <= 4194304 &&
           count == env->GetArrayLength(gray);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_valoser_futacha_shared_media_analysis_TrackingNative_create(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(futacha_tracker_create()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_valoser_futacha_shared_media_analysis_TrackingNative_destroy(JNIEnv*, jobject, jlong pointer) {
    futacha_tracker_destroy(tracker(pointer));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_valoser_futacha_shared_media_analysis_TrackingNative_seed(
    JNIEnv* env, jobject, jlong pointer, jbyteArray gray, jint width, jint height,
    jfloat x, jfloat y, jfloat w, jfloat h) {
    if (!pointer || !validFrame(env, gray, width, height)) return -1;
    jbyte* bytes = env->GetByteArrayElements(gray, nullptr);
    if (!bytes) return -1;
    const int status = futacha_tracker_seed(tracker(pointer), bytes, width, height, x, y, w, h);
    env->ReleaseByteArrayElements(gray, bytes, JNI_ABORT);
    return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_valoser_futacha_shared_media_analysis_TrackingNative_step(
    JNIEnv* env, jobject, jlong pointer, jbyteArray gray, jint width, jint height,
    jboolean sceneCut, jfloatArray output) {
    if (!pointer || !validFrame(env, gray, width, height) || !output || env->GetArrayLength(output) != 5) return -1;
    jbyte* bytes = env->GetByteArrayElements(gray, nullptr);
    if (!bytes) return -1;
    float result[5];
    const int status = futacha_tracker_step(tracker(pointer), bytes, width, height, sceneCut ? 1 : 0, result);
    env->ReleaseByteArrayElements(gray, bytes, JNI_ABORT);
    if (status == 0) env->SetFloatArrayRegion(output, 0, 5, result);
    return status;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_valoser_futacha_shared_media_analysis_TrackingNative_extractOrb(
    JNIEnv* env, jobject, jbyteArray gray, jint width, jint height) {
    if (!validFrame(env, gray, width, height)) return nullptr;
    // Fixed-size stack buffer avoids an allocation exception crossing the JNI boundary.
    int8_t output[40016];
    jbyte* bytes = env->GetByteArrayElements(gray, nullptr);
    if (!bytes) return nullptr;
    const int size = futacha_orb_extract(bytes, width, height, output, sizeof(output));
    env->ReleaseByteArrayElements(gray, bytes, JNI_ABORT);
    if (size < 0) return nullptr;
    auto result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size, output);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_valoser_futacha_shared_media_analysis_TrackingNative_matchOrb(
    JNIEnv* env, jobject, jbyteArray left, jbyteArray right) {
    if (!left || !right) return -1;
    int a_size = env->GetArrayLength(left), b_size = env->GetArrayLength(right);
    if (a_size < 16 || b_size < 16 || a_size > 40016 || b_size > 40016) return -1;
    auto a = env->GetByteArrayElements(left, nullptr);
    if (!a) return -1;
    auto b = env->GetByteArrayElements(right, nullptr);
    if (!b) { env->ReleaseByteArrayElements(left, a, JNI_ABORT); return -1; }
    int result = futacha_orb_match(a, a_size, b, b_size);
    env->ReleaseByteArrayElements(right, b, JNI_ABORT);
    env->ReleaseByteArrayElements(left, a, JNI_ABORT);
    return result;
}
