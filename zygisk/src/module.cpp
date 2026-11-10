#include <android/log.h>
#include <dobby.h>
#include <jni.h>
#include <lsplant.hpp>

#include <cstring>
#include <memory>
#include <string_view>

#include "elf_symbols.h"
#include "zygisk.hpp"

#define LOG_TAG "fcmfix-zygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
// zygisk/src/dex_blob.S
[[gnu::visibility("hidden")]] extern const unsigned char fcmfix_dex_start[];
[[gnu::visibility("hidden")]] extern const unsigned char fcmfix_dex_end[];
}

namespace {

/** NeoZygisk sets this when it drives the module on a system_server that is already serving. */
constexpr uint32_t kLateInject = 1u << 30;
constexpr int kPerUserRange = 100000;
constexpr int kSystemAppId = 1000;
/** Battery.apk: sharedUserId android.uid.system, default process. */
constexpr const char *kBatteryProcess = "com.oplus.battery";

constexpr const char *kHooksClass = "com.kooritea.fcmfix.zygisk.Hooks";
constexpr const char *kEntryClass = "com.kooritea.fcmfix.zygisk.ZygiskEntry";

std::unique_ptr<ElfSymbols> g_art;

jobject NativeHook(JNIEnv *env, jclass, jobject target, jobject hooker, jobject callback) {
    return lsplant::Hook(env, target, hooker, callback);
}

jboolean NativeDeoptimize(JNIEnv *env, jclass, jobject target) {
    return lsplant::Deoptimize(env, target) ? JNI_TRUE : JNI_FALSE;
}

/** Logs and clears a pending Java exception; true when there was one. */
bool Failed(JNIEnv *env, const char *step) {
    if (!env->ExceptionCheck()) return false;
    LOGE("%s threw", step);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

jobject LoadHookDex(JNIEnv *env) {
    jclass loader_class = env->FindClass("java/lang/ClassLoader");
    if (Failed(env, "find ClassLoader")) return nullptr;
    jmethodID get_system = env->GetStaticMethodID(loader_class, "getSystemClassLoader",
                                                  "()Ljava/lang/ClassLoader;");
    jobject parent = env->CallStaticObjectMethod(loader_class, get_system);
    if (Failed(env, "getSystemClassLoader")) return nullptr;

    // The bytes are this library's own read-only data, and an injected library is never unloaded.
    jobject buffer = env->NewDirectByteBuffer(const_cast<unsigned char *>(fcmfix_dex_start),
                                              fcmfix_dex_end - fcmfix_dex_start);
    jclass memory_loader = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (Failed(env, "find InMemoryDexClassLoader")) return nullptr;
    jmethodID constructor = env->GetMethodID(memory_loader, "<init>",
                                             "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jobject loader = env->NewObject(memory_loader, constructor, buffer, parent);
    if (Failed(env, "load the hook dex")) return nullptr;
    return loader;
}

jclass LoadClass(JNIEnv *env, jobject loader, const char *name) {
    jclass loader_class = env->FindClass("java/lang/ClassLoader");
    jmethodID load = env->GetMethodID(loader_class, "loadClass",
                                      "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring class_name = env->NewStringUTF(name);
    auto loaded = static_cast<jclass>(env->CallObjectMethod(loader, load, class_name));
    if (Failed(env, name)) return nullptr;
    return loaded;
}

/**
 * Starts the hook engine and hands over to the Java entry.
 *
 * Returns whether the library has to stay loaded. Once the hook engine was started, ART's own
 * functions may already jump into this library, so every later failure still returns true.
 */
bool Inject(JNIEnv *env, bool system_server, bool late, jstring process_name) {
    g_art = ElfSymbols::Open("/libart.so");
    if (!g_art) {
        LOGE("cannot read libart.so symbols; nothing hooked");
        return false;
    }

    static const lsplant::InitInfo init_info = [] {
        lsplant::InitInfo info;
        info.inline_hooker = [](void *target, void *replacement) -> void * {
            void *backup = nullptr;
            return DobbyHook(target, replacement, &backup) == 0 ? backup : nullptr;
        };
        info.inline_unhooker = [](void *target) { return DobbyDestroy(target) == 0; };
        info.art_symbol_resolver = [](std::string_view name) { return g_art->Find(name); };
        info.art_symbol_prefix_resolver = [](std::string_view prefix) {
            return g_art->FindPrefix(prefix);
        };
        info.generated_class_name = "FcmFixHook_";
        info.generated_source_name = "fcmfix";
        return info;
    }();
    if (!lsplant::Init(env, init_info)) {
        LOGE("ART hook engine failed to start (%zu libart symbols read); nothing hooked",
             g_art->symbol_count());
        return true;
    }

    jobject loader = LoadHookDex(env);
    if (loader == nullptr) return true;
    jclass hooks = LoadClass(env, loader, kHooksClass);
    jclass entry = LoadClass(env, loader, kEntryClass);
    if (hooks == nullptr || entry == nullptr) return true;

    const JNINativeMethod natives[] = {
        {"nativeHook",
         "(Ljava/lang/reflect/Executable;Ljava/lang/Object;Ljava/lang/reflect/Method;)"
         "Ljava/lang/reflect/Method;",
         reinterpret_cast<void *>(NativeHook)},
        {"nativeDeoptimize", "(Ljava/lang/reflect/Executable;)Z",
         reinterpret_cast<void *>(NativeDeoptimize)},
    };
    env->RegisterNatives(hooks, natives, sizeof(natives) / sizeof(natives[0]));
    if (Failed(env, "register natives")) return true;

    if (system_server) {
        jmethodID start = env->GetStaticMethodID(entry, "systemServer", "(Z)V");
        if (Failed(env, "find ZygiskEntry.systemServer")) return true;
        env->CallStaticVoidMethod(entry, start, late ? JNI_TRUE : JNI_FALSE);
    } else {
        jmethodID start = env->GetStaticMethodID(entry, "app", "(Ljava/lang/String;)V");
        if (Failed(env, "find ZygiskEntry.app")) return true;
        env->CallStaticVoidMethod(entry, start, process_name);
    }
    if (!Failed(env, "ZygiskEntry")) {
        LOGI("injected into %s%s", system_server ? "system_server" : kBatteryProcess,
             late ? " (late)" : "");
    }
    return true;
}

class FcmFixModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        battery_ = false;
        if (args->uid % kPerUserRange != kSystemAppId || args->nice_name == nullptr) return;
        if (args->is_child_zygote != nullptr && *args->is_child_zygote) return;
        const char *name = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (name == nullptr) return;
        battery_ = strcmp(name, kBatteryProcess) == 0;
        env_->ReleaseStringUTFChars(args->nice_name, name);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        if (!battery_ || !Inject(env_, false, false, args->nice_name)) Unload();
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs *args) override {
        const bool late = (static_cast<uint32_t>(args->runtime_flags) & kLateInject) != 0;
        if (!Inject(env_, true, late, nullptr)) Unload();
    }

private:
    /** Every process that is not hooked drops the library, leaving nothing of it mapped. */
    void Unload() { api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); }

    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    bool battery_ = false;
};

}  // namespace

REGISTER_ZYGISK_MODULE(FcmFixModule)
