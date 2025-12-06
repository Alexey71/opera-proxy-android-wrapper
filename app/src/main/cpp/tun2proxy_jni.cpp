#include <jni.h>
#include <dlfcn.h>
#include <string>
#include "tun2proxy.h"

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

// Глобальные переменные для handle и указателей на функции
static void* t2p_handle = nullptr;
static pfn_tun2proxy_with_fd_run t2p_run = nullptr;
static pfn_tun2proxy_stop t2p_stop = nullptr;

// Хелпер для загрузки библиотеки (делаем 1 раз)
bool load_tun2proxy_lib() {
    if (t2p_handle) return true;

    t2p_handle = dlopen("libtun2proxy.so", RTLD_LAZY);
    if (!t2p_handle) return false;

    t2p_run = (pfn_tun2proxy_with_fd_run) dlsym(t2p_handle, "tun2proxy_with_fd_run");
    t2p_stop = (pfn_tun2proxy_stop) dlsym(t2p_handle, "tun2proxy_stop");

    if (!t2p_run || !t2p_stop) {
        // Если не нашли обязательные функции
        dlclose(t2p_handle);
        t2p_handle = nullptr;
        return false;
    }
    return true;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_operaproxy_ProxyVpnService_startTun2proxy(
        JNIEnv *env,
        jclass clazz,
        jstring proxy_url,
        jint tun_fd,
        jboolean close_fd_on_drop,
        jchar tun_mtu,
        jint dns_strategy,
        jint verbosity) {

    if (!load_tun2proxy_lib()) return -1;

    const char *nativeString = env->GetStringUTFChars(proxy_url, nullptr);

    // ВАЖНО: передаем close_fd_on_drop как есть (теперь будем слать true из Kotlin)
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
    
    // dlclose НЕ делаем, держим библиотеку в памяти
    return r;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_operaproxy_ProxyVpnService_stopTun2proxy(JNIEnv *env, jclass clazz) {
    if (!t2p_handle || !t2p_stop) {
        return -1; // Библиотека не загружена или функция не найдена
    }
    // Явно вызываем остановку внутри Rust
    return t2p_stop();
}