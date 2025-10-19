#include <jni.h>
#include <string>
#include <android/log.h>
#include "opus.h"

#define LOG_TAG "OpusJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_fear_OpusCodec_00024Encoder_nativeCreate(JNIEnv *env, jobject thiz,
                                                   jint sample_rate, jint channels, jint application) {
    int error;
    OpusEncoder *encoder = opus_encoder_create(sample_rate, channels, application, &error);
    if (error != OPUS_OK) {
        LOGE("Failed to create encoder: %s", opus_strerror(error));
        return 0;
    }
    LOGI("Encoder created: %dHz, %d channels", sample_rate, channels);
    return reinterpret_cast<jlong>(encoder);
}

JNIEXPORT void JNICALL
Java_com_fear_OpusCodec_00024Encoder_nativeSetBitrate(JNIEnv *env, jobject thiz,
                                                       jlong handle, jint bitrate) {
    OpusEncoder *encoder = reinterpret_cast<OpusEncoder *>(handle);
    if (encoder) {
        opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    }
}

JNIEXPORT jint JNICALL
Java_com_fear_OpusCodec_00024Encoder_nativeEncode(JNIEnv *env, jobject thiz,
                                                    jlong handle,
                                                    jshortArray pcm,
                                                    jint frame_size,
                                                    jbyteArray output,
                                                    jint max_output_size) {
    OpusEncoder *encoder = reinterpret_cast<OpusEncoder *>(handle);
    if (!encoder) {
        LOGE("Encoder handle is null");
        return -1;
    }

    jshort *pcm_data = env->GetShortArrayElements(pcm, nullptr);
    jbyte *output_data = env->GetByteArrayElements(output, nullptr);

    int encoded_size = opus_encode(encoder, pcm_data, frame_size,
                                    reinterpret_cast<unsigned char *>(output_data),
                                    max_output_size);

    env->ReleaseShortArrayElements(pcm, pcm_data, JNI_ABORT);
    env->ReleaseByteArrayElements(output, output_data, 0);

    if (encoded_size < 0) {
        LOGE("Encode error: %s", opus_strerror(encoded_size));
        return -1;
    }

    return encoded_size;
}

JNIEXPORT void JNICALL
Java_com_fear_OpusCodec_00024Encoder_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    OpusEncoder *encoder = reinterpret_cast<OpusEncoder *>(handle);
    if (encoder) {
        opus_encoder_destroy(encoder);
        LOGI("Encoder destroyed");
    }
}

JNIEXPORT jlong JNICALL
Java_com_fear_OpusCodec_00024Decoder_nativeCreate(JNIEnv *env, jobject thiz,
                                                   jint sample_rate, jint channels) {
    int error;
    OpusDecoder *decoder = opus_decoder_create(sample_rate, channels, &error);
    if (error != OPUS_OK) {
        LOGE("Failed to create decoder: %s", opus_strerror(error));
        return 0;
    }
    LOGI("Decoder created: %dHz, %d channels", sample_rate, channels);
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT jint JNICALL
Java_com_fear_OpusCodec_00024Decoder_nativeDecode(JNIEnv *env, jobject thiz,
                                                    jlong handle,
                                                    jbyteArray opus_data,
                                                    jint opus_size,
                                                    jshortArray output,
                                                    jint frame_size) {
    OpusDecoder *decoder = reinterpret_cast<OpusDecoder *>(handle);
    if (!decoder) {
        LOGE("Decoder handle is null");
        return -1;
    }

    jbyte *opus_bytes = env->GetByteArrayElements(opus_data, nullptr);
    jshort *output_data = env->GetShortArrayElements(output, nullptr);

    int decoded_samples = opus_decode(decoder,
                                       reinterpret_cast<const unsigned char *>(opus_bytes),
                                       opus_size,
                                       output_data,
                                       frame_size,
                                       0);

    env->ReleaseByteArrayElements(opus_data, opus_bytes, JNI_ABORT);
    env->ReleaseShortArrayElements(output, output_data, 0);

    if (decoded_samples < 0) {
        LOGE("Decode error: %s", opus_strerror(decoded_samples));
        return -1;
    }

    return decoded_samples;
}

JNIEXPORT void JNICALL
Java_com_fear_OpusCodec_00024Decoder_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    OpusDecoder *decoder = reinterpret_cast<OpusDecoder *>(handle);
    if (decoder) {
        opus_decoder_destroy(decoder);
        LOGI("Decoder destroyed");
    }
}

}
