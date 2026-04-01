/*
 * JNI platform-specific types for Windows (cross-compilation from Linux).
 * Mirrors the standard win32/jni_md.h from any JDK.
 */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL   __stdcall

typedef long            jint;
typedef long long       jlong;
typedef signed char     jbyte;

#endif /* _JAVASOFT_JNI_MD_H_ */
