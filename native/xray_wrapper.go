package main

/*
#include <stdlib.h>
#include <android/log.h>
#include <stdbool.h>

static void log_info (const char* s){
    __android_log_print(ANDROID_LOG_INFO , "libxray", "%s", s);
}
static void log_error(const char* s){
    __android_log_print(ANDROID_LOG_ERROR, "libxray", "%s", s);
}

void XrayNotifyActive(void);
void XrayNotifyListening(void);
void XrayNotifyStopped(void);
*/
import "C"

import (
        "encoding/json"
        "fmt"
        "os"
        "path/filepath"
        "unsafe"

        "github.com/xtls/xray-core/core"
        "github.com/xtls/xray-core/common/errors"

        // built-in protocols + config loaders
        _ "github.com/xtls/xray-core/main/distro/all"
        _ "github.com/xtls/xray-core/main/json"
        _ "github.com/xtls/xray-core/main/yaml"
        _ "github.com/xtls/xray-core/main/toml"
)

var instance *core.Instance

// --------------------------------------------------------------------
// StartXray  (exported to JNI)
// --------------------------------------------------------------------

//export StartXray
func StartXray(cfg *C.char) C.int {
        path := C.GoString(cfg)

        // make sure xray can find geoip / geosite next to the config
        dataDir := filepath.Dir(path)
        os.Setenv("XRAY_LOCATION", dataDir)
        os.Setenv("XRAY_LOCATION_ASSET", dataDir)

        raw, err := os.ReadFile(path)
        if err != nil {
                clogErr("open: %v", err)
                return -1
        }

        var tmp any
        if err = json.Unmarshal(raw, &tmp); err != nil {
                clogErr("json: %v", err)
                return -2
        }

        inst, err := core.StartInstance("json", raw)
        if err != nil {
                clogErr("StartInstance: %v", err)
                if xe, ok := err.(*errors.Error); ok {
                        dumpChain(xe, 0)
                }
                return -3
        }
        instance = inst
        C.XrayNotifyListening() // tell UI we're up and waiting
        clogInfo("xray-core started")
        return 0
}

// --------------------------------------------------------------------
// helpers
// --------------------------------------------------------------------

func dumpChain(e *errors.Error, n int) {
        if e == nil {
                return
        }
        clogErr("err[%d] %v", n, e)
        if in := e.Unwrap(); in != nil {
                if x, ok := in.(*errors.Error); ok {
                        dumpChain(x, n+1)
                }
        }
}

func clogInfo(f string, a ...any) {
        msg := C.CString(fmt.Sprintf(f, a...))
        defer C.free(unsafe.Pointer(msg))
        C.log_info(msg)
}

func clogErr(f string, a ...any) {
        msg := C.CString(fmt.Sprintf(f, a...))
        defer C.free(unsafe.Pointer(msg))
        C.log_error(msg)
}

// ---------------------------------------------------------------------
// StopXray  –  exported to JNI
// ---------------------------------------------------------------------

//export StopXray
func StopXray() C.int {
    if instance == nil {
        clogErr("StopXray: not running")
        return -1
    }
    if err := instance.Close(); err != nil {
        clogErr("StopXray: %v", err)
        return -2
    }
    instance = nil
    C.XrayNotifyStopped() // tell UI we're fully stopped
    clogInfo("xray-core stopped")
    return 0
}

// required for -buildmode=c-shared
func main() {}
