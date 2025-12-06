#include <jni.h>
#include <dlfcn.h>
#include <string>

#include "tun2proxy.h"

// Сигнатуры из wiki tun2proxy :contentReference[oaicite:3]{index=3}
typedef int (*pfn_tun2proxy_with_fd_run)(
        const char *proxy_url,
        int tun_fd,
        bool close_fd_on_drop,
        bool background,
        unsigned short tun_mtu,
        Tun2proxyDns dns_strategy,
        Tun2proxyVerbosity verbosity);

static void* t2p_handle = nullptr;

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

    if (t2p_handle) {
        // уже запущено
        return -3;
    }

    // грузим libtun2proxy.so (из jniLibs/arm64-v8a)
    t2p_handle = dlopen("libtun2proxy.so", RTLD_LAZY);
    if (!t2p_handle) {
        return -1;
    }

    auto t2p_run = (pfn_tun2proxy_with_fd_run) dlsym(t2p_handle, "tun2proxy_with_fd_run");
    if (!t2p_run) {
        dlclose(t2p_handle);
        t2p_handle = nullptr;
        return -2;
    }

    const char *nativeString = env->GetStringUTFChars(proxy_url, nullptr);

    int r = t2p_run(
            nativeString,
            tun_fd,
            (bool) close_fd_on_drop,
            false, // background = false, мы запускаем в отдельном Java-потоке
            (unsigned short) tun_mtu,
            (Tun2proxyDns) dns_strategy,
            (Tun2proxyVerbosity) verbosity
    );

    env->ReleaseStringUTFChars(proxy_url, nativeString);

    dlclose(t2p_handle);
    t2p_handle = nullptr;

    return r;
}
