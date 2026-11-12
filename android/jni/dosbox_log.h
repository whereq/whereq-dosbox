/*
 * dosbox_log.h - tiny logging shim for the Android glue code.
 *
 * Uses Android's logcat so the native side is visible in `adb logcat`.
 */
#ifndef DOSBOX_LOG_H
#define DOSBOX_LOG_H

#include <android/log.h>

#define DOSBOX_LOG(...) \
    __android_log_print(ANDROID_LOG_INFO, "whereq-dosbox", __VA_ARGS__)

#endif /* DOSBOX_LOG_H */
