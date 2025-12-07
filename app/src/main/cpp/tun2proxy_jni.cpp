#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <android/log.h>
#include "tun2proxy.h"

// Logcat тэг
#define LOG_TAG "OperaProxy"

// Определяем типы функций
typedef int (*pfn_tun2proxy_with_fd_run)(
        const char *proxy_url,
        int tun_fd,
        bool close_fd_on_drop,
        bool background,
        unsigned short tun_mtu,
        Tun2proxyDns dns_strategy,
        Tun2proxyVerbosity verbosity);

typedef int (*pfn_tun2proxy_stop)(void);

// Добавляем тип для установки коллбека
typedef void (*pfn_tun2proxy_set_log_callback)(
        void (*callback)(Tun2proxyVerbosity, const char*, void*),
        void *ctx);

// Глобальные переменные
static void* t2p_handle = nullptr;
static pfn_tun2proxy_with_fd_run t2p_run = nullptr;
static pfn_tun2proxy_stop t2p_stop = nullptr;
static pfn_tun2proxy_set_log_callback t2p_set_log = nullptr;

// Глобальные переменные для JNI обратного вызова
static JavaVM* g_jvm = nullptr;
static jobject g_service_ref = nullptr;

// Сохраняем JVM при загрузке библиотеки
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// Хелпер для загрузки библиотеки
bool load_tun2proxy_lib() {
    if (t2p_handle) return true;

    t2p_handle = dlopen("libtun2proxy.so", RTLD_LAZY);
    if (!t2p_handle) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to load libtun2proxy.so");
        return false;
    }

    t2p_run = (pfn_tun2proxy_with_fd_run) dlsym(t2p_handle, "tun2proxy_with_fd_run");
    t2p_stop = (pfn_tun2proxy_stop) dlsym(t2p_handle, "tun2proxy_stop");
    t2p_set_log = (pfn_tun2proxy_set_log_callback) dlsym(t2p_handle, "tun2proxy_set_log_callback");

    if (!t2p_run || !t2p_stop) {
        dlclose(t2p_handle);
        t2p_handle = nullptr;
        return false;
    }
    return true;
}

// Callback функция, которую будет вызывать Rust
void tun2proxy_log_callback(Tun2proxyVerbosity level, const char* msg, void* ctx) {
    // 1. Пишем в Logcat с тегом "tun2proxy" (всегда)
    // Маппинг уровней: Rust Verbosity -> Android Priority
    int android_prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case Tun2proxyVerbosity_Error: android_prio = ANDROID_LOG_ERROR; break;
        case Tun2proxyVerbosity_Warn:  android_prio = ANDROID_LOG_WARN; break;
        case Tun2proxyVerbosity_Info:  android_prio = ANDROID_LOG_INFO; break;
        default: break;
    }
    __android_log_print(android_prio, LOG_TAG, "%s", msg);

    // 2. Отправляем в Java (UI), если есть ссылка на сервис
    if (g_jvm && g_service_ref) {
        JNIEnv* env;
        bool attached = false;
        int getEnvStat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);

        if (getEnvStat == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) != 0) return;
            attached = true;
        }

        jclass cls = env->GetObjectClass(g_service_ref);
        jmethodID mid = env->GetMethodID(cls, "onTun2ProxyLog", "(Ljava/lang/String;)V");
        if (mid) {
            jstring jMsg = env->NewStringUTF(msg);
            env->CallVoidMethod(g_service_ref, mid, jMsg);
            env->DeleteLocalRef(jMsg);
        }
        env->DeleteLocalRef(cls);

        if (attached) {
            g_jvm->DetachCurrentThread();
        }
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_operaproxy_ProxyVpnService_startTun2proxy(
        JNIEnv *env,
        jclass clazz,
        jobject service_instance,
        jstring proxy_url,
        jint tun_fd,
        jboolean close_fd_on_drop,
        jchar tun_mtu,
        jint dns_strategy,
        jint verbosity) {

    if (!load_tun2proxy_lib()) return -1;

    if (g_service_ref) {
        env->DeleteGlobalRef(g_service_ref);
    }
    g_service_ref = env->NewGlobalRef(service_instance);

    if (t2p_set_log) {
        t2p_set_log(tun2proxy_log_callback, nullptr);
    } else {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Symbol tun2proxy_set_log_callback not found");
    }

    const char *nativeString = env->GetStringUTFChars(proxy_url, nullptr);

    int r = t2p_run(
            nativeString,
            tun_fd,
            (bool) close_fd_on_drop, 
            false,
            (unsigned short) tun_mtu,
            (Tun2proxyDns) dns_strategy,
            (Tun2proxyVerbosity) verbosity
    );

    env->ReleaseStringUTFChars(proxy_url, nativeString);

    if (g_service_ref) {
        env->DeleteGlobalRef(g_service_ref);
        g_service_ref = nullptr;
    }

    return r;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_operaproxy_ProxyVpnService_stopTun2proxy(JNIEnv *env, jclass clazz) {
    if (!t2p_handle || !t2p_stop) {
        return -1; 
    }
    return t2p_stop();
}