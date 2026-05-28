//
// Created by S on 2026/5/28.
//

#include <jni.h>

#ifndef ANDROIDSERIALPORTDEMO_YQSERIALPORT_H
#define ANDROIDSERIALPORTDEMO_YQSERIALPORT_H
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     android_serialport_SerialPort
 * Method:    open
 * Signature: (Ljava/lang/String;IIIII)Ljava/io/FileDescriptor;
 */
JNIEXPORT jobject JNICALL Java_com_mind_yqserialport_SerialPort_open
        (JNIEnv *, jobject, jstring, jint, jint, jint, jint, jint);

/*
 * Class:     android_serialport_SerialPort
 * Method:    close
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_com_mind_yqserialport_SerialPort_close
(JNIEnv *, jobject);

#ifdef __cplusplus
}
#endif
#endif
/* Header for class android_serialport_SerialPort_Builder */

#ifndef _Included_com_mind_yqserialport_SerialPort_Builder
#define _Included_com_mind_yqserialport_SerialPort_Builder
#ifdef __cplusplus
extern "C" {
#endif
#ifdef __cplusplus
}
#endif
#endif //ANDROIDSERIALPORTDEMO_YQSERIALPORT_H

