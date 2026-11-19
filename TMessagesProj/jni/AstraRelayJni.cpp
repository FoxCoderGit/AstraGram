#include <jni.h>
#include <mutex>
#include <string>
#include "tgnet/AstraRelay.h"

namespace {
JavaVM* relayVM = nullptr;
jclass relayClass = nullptr;
jmethodID proofMethod = nullptr;
jmethodID eventMethod = nullptr;
std::mutex bridgeMutex;

struct RelayEnv {
    JNIEnv* env = nullptr;
    bool detach = false;
    RelayEnv() {
        if (!relayVM) return;
        if (relayVM->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (relayVM->AttachCurrentThread(&env,nullptr) == JNI_OK) detach = true;
            else env = nullptr;
        }
    }
    ~RelayEnv() { if (detach) relayVM->DetachCurrentThread(); }
};
std::string string(JNIEnv* env,jstring value) {
    if (!value || env->GetStringUTFLength(value) > 16384) return {};
    const char* chars = env->GetStringUTFChars(value,nullptr);
    if (!chars) { env->ExceptionClear(); return {}; }
    std::string result(chars); env->ReleaseStringUTFChars(value,chars); return result;
}
std::vector<std::string> split(const std::string& text) {
    std::vector<std::string> result; size_t start = 0;
    while (start < text.size()) { size_t end = text.find(',',start); if (end == std::string::npos) end=text.size(); if (end>start) result.push_back(text.substr(start,end-start));start=end+1; }
    return result;
}
std::string connectionProof(const std::string& token,const std::string& address,uint16_t port) {
    RelayEnv current; if (!current.env) return {};
    JNIEnv* env=current.env;
    jstring javaToken=env->NewStringUTF(token.c_str()),javaAddress=env->NewStringUTF(address.c_str());
    auto result=static_cast<jstring>(env->CallStaticObjectMethod(relayClass,proofMethod,javaToken,javaAddress,jint(port)));
    std::string proof;
    if (env->ExceptionCheck()) env->ExceptionClear(); else proof=string(env,result);
    if(result)env->DeleteLocalRef(result);env->DeleteLocalRef(javaToken);env->DeleteLocalRef(javaAddress);
    return proof;
}
void relayEvent(int type,int64_t delay) {
    RelayEnv current; if(!current.env)return;
    current.env->CallStaticVoidMethod(relayClass,eventMethod,jint(type),jlong(delay));
    if(current.env->ExceptionCheck())current.env->ExceptionClear();
}
}

extern "C" JNIEXPORT void JNICALL Java_org_telegram_messenger_AstraRelay_nativeConfigure(
        JNIEnv* env,jclass klass,jint permission,jlong validity,jlong retry,jstring host,jstring ips,jint port,
        jstring path,jstring pins,jstring token,jboolean network) {
    {
        std::lock_guard<std::mutex> lock(bridgeMutex);
        if(!relayClass) {
            env->GetJavaVM(&relayVM);
            relayClass=static_cast<jclass>(env->NewGlobalRef(klass));
            proofMethod=env->GetStaticMethodID(klass,"nativeConnectionHeaders","(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;");
            eventMethod=env->GetStaticMethodID(klass,"onNativeRelayEvent","(IJ)V");
            if(!proofMethod||!eventMethod)return;
        }
    }
    astragram::RelayConfiguration config;
    config.managed=true;config.networkAvailable=network;
    config.permission=permission==1?astragram::RelayPermission::Allowed:permission==2?astragram::RelayPermission::Denied:astragram::RelayPermission::Unknown;
    config.validityMs=validity;config.retryMs=retry;
    config.host=string(env,host);config.ips=split(string(env,ips));config.port=port>0&&port<=65535?port:0;
    config.path=string(env,path);config.pins=split(string(env,pins));config.token=string(env,token);
    astragram::configureAstraRelay(std::move(config),connectionProof,relayEvent);
}
