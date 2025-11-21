// xray_jni.c
#include <jni.h>
#include <stdbool.h>

// Existing exports from Go
extern int StartXray(const char*);
extern jint StopXray(void);

/* ────────────────────────────────────────────────────────────── */
/*   Signals → Kotlin (com.cryptostorm.xray.XraySignals)          */
/* ────────────────────────────────────────────────────────────── */
static JavaVM* g_vm = NULL;
static jclass g_clsSignals = NULL;
static jmethodID g_midActive = NULL;
static jmethodID g_midListening = NULL;
static jmethodID g_midStopped = NULL;

static JNIEnv* getEnv(bool* didAttach) {
    *didAttach = false;
    if (!g_vm) return NULL;
    JNIEnv* env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return NULL;
        *didAttach = true;
    }
    return env;
}

static void callVoidNoArgs(jmethodID mid) {
    if (!g_vm || !g_clsSignals || !mid) return;
    bool didAttach = false;
    JNIEnv* env = getEnv(&didAttach);
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, g_clsSignals, mid);
    if (didAttach) (*g_vm)->DetachCurrentThread(g_vm);
}

/* These are callable from Go via cgo */
void XrayNotifyActive(void)    { callVoidNoArgs(g_midActive); }
void XrayNotifyListening(void) { callVoidNoArgs(g_midListening); }
void XrayNotifyStopped(void)   { callVoidNoArgs(g_midStopped); }

/* ────────────────────────────────────────────────────────────── */
/*   JNI lifecycle                                                */
/* ────────────────────────────────────────────────────────────── */
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return 0;

    jclass localCls = (*env)->FindClass(env, "com/cryptostorm/xray/XraySignals");
    if (!localCls) return 0;
    g_clsSignals = (*env)->NewGlobalRef(env, localCls);
    (*env)->DeleteLocalRef(env, localCls);
    if (!g_clsSignals) return 0;

    g_midActive    = (*env)->GetStaticMethodID(env, g_clsSignals, "notifyActive",    "()V");
    g_midListening = (*env)->GetStaticMethodID(env, g_clsSignals, "notifyListening", "()V");
    g_midStopped   = (*env)->GetStaticMethodID(env, g_clsSignals, "notifyStopped",   "()V");
    if (!g_midActive || !g_midListening || !g_midStopped) return 0;

    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    if (g_vm && (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        if (g_clsSignals) {
            (*env)->DeleteGlobalRef(env, g_clsSignals);
            g_clsSignals = NULL;
        }
    }
    g_vm = NULL;
}

/* ────────────────────────────────────────────────────────────── */
/*   Existing JNI bridge                                          */
/* ────────────────────────────────────────────────────────────── */
JNIEXPORT jint JNICALL
Java_com_cryptostorm_xray_XrayBridge_StartXray(JNIEnv *env, jobject thiz, jstring path) {
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    int result = StartXray(cpath);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_cryptostorm_xray_XrayBridge_stopXray(JNIEnv *env, jclass clazz) {
    return StopXray();
}
