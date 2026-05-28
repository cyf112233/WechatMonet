#include <android/log.h>
#include <jni.h>

#include <dlfcn.h>
#include <string>

#include "zygisk/api.hpp"

namespace {

constexpr const char *kTag = "WechatMonet";
constexpr const char *kTargetPackage = "com.tencent.mm";
constexpr const char *kBridgeClass = "com/wechat/monet/injector/Bridge";
constexpr const char *kBridgeMethod = "initialize";
constexpr const char *kBridgeSig = "(Landroid/app/Application;)V";
constexpr const char *kActivityThread = "android/app/ActivityThread";

std::string JStringToString(JNIEnv *env, jstring value) {
    if (env == nullptr || value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void Log(const char *msg) {
    __android_log_write(ANDROID_LOG_INFO, kTag, msg);
}

class WechatMonetModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (api_ == nullptr || env_ == nullptr || args == nullptr) return;
        process_name_ = JStringToString(env_, args->nice_name);
        if (process_name_ != kTargetPackage) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        if (api_ == nullptr || env_ == nullptr || args == nullptr) return;
        if (process_name_ != kTargetPackage) return;

        jclass activity_thread = env_->FindClass(kActivityThread);
        if (activity_thread == nullptr) {
            env_->ExceptionClear();
            Log("ActivityThread not found");
            return;
        }

        jmethodID current_application = env_->GetStaticMethodID(
            activity_thread,
            "currentApplication",
            "()Landroid/app/Application;"
        );
        if (current_application == nullptr) {
            env_->ExceptionClear();
            Log("currentApplication missing");
            return;
        }

        jobject application = env_->CallStaticObjectMethod(activity_thread, current_application);
        if (env_->ExceptionCheck() || application == nullptr) {
            env_->ExceptionClear();
            Log("Unable to obtain Application");
            return;
        }

        jclass bridge = env_->FindClass(kBridgeClass);
        if (bridge == nullptr) {
            env_->ExceptionClear();
            Log("Bridge class not found");
            return;
        }

        jmethodID initialize = env_->GetStaticMethodID(bridge, kBridgeMethod, kBridgeSig);
        if (initialize == nullptr) {
            env_->ExceptionClear();
            Log("Bridge.initialize missing");
            return;
        }

        env_->CallStaticVoidMethod(bridge, initialize, application);
        if (env_->ExceptionCheck()) {
            env_->ExceptionDescribe();
            env_->ExceptionClear();
            Log("Bridge.initialize failed");
            return;
        }

        Log("Injected into com.tencent.mm");
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    std::string process_name_;
};

}  // namespace

REGISTER_ZYGISK_MODULE(WechatMonetModule)
