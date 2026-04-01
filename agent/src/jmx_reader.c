/*
 * JVMMonitor - JMX MBean Reader Implementation
 * Accesses MBeanServer via JNI to enumerate and read MBean attributes.
 */
#include "jvmmon/jmx_reader.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

/* New message type for JMX data */
#ifndef JVMMON_MSG_JMX_DATA
#define JVMMON_MSG_JMX_DATA 0xB0
#endif

/* ── JNI helpers to call MBeanServer ────────────────── */

static jobject get_platform_mbean_server(JNIEnv *env) {
    jclass mf = (*env)->FindClass(env, "java/lang/management/ManagementFactory");
    if (mf == NULL) return NULL;
    jmethodID getPBS = (*env)->GetStaticMethodID(env, mf,
        "getPlatformMBeanServer", "()Ljavax/management/MBeanServer;");
    if (getPBS == NULL) { (*env)->DeleteLocalRef(env, mf); return NULL; }
    jobject server = (*env)->CallStaticObjectMethod(env, mf, getPBS);
    (*env)->DeleteLocalRef(env, mf);
    return server;
}

static void send_mbean_list_impl(jmx_reader_t *jr, JNIEnv *env) {
    jobject server = get_platform_mbean_server(env);
    if (server == NULL) return;

    /* server.queryNames(null, null) → Set<ObjectName> */
    jclass serverClass = (*env)->GetObjectClass(env, server);
    jmethodID queryNames = (*env)->GetMethodID(env, serverClass, "queryNames",
        "(Ljavax/management/ObjectName;Ljavax/management/QueryExp;)Ljava/util/Set;");
    if (queryNames == NULL) goto cleanup;

    jobject nameSet = (*env)->CallObjectMethod(env, server, queryNames, NULL, NULL);
    if (nameSet == NULL) goto cleanup;

    /* Convert Set to array */
    jclass setClass = (*env)->GetObjectClass(env, nameSet);
    jmethodID toArray = (*env)->GetMethodID(env, setClass, "toArray",
        "()[Ljava/lang/Object;");
    jobjectArray arr = (jobjectArray)(*env)->CallObjectMethod(env, nameSet, toArray);
    if (arr == NULL) goto cleanup;

    jint count = (*env)->GetArrayLength(env, arr);

    /* Encode MBean list: subtype(1) + timestamp(8) + count(u16) + names */
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;
    off += protocol_encode_u8(payload + off, JMX_SUBTYPE_MBEAN_LIST);
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u16(payload + off, (uint16_t)(count > 1000 ? 1000 : count));

    jint i;
    for (i = 0; i < count && i < 1000; i++) {
        if ((*env)->PushLocalFrame(env, 16) < 0) break;
        jobject objName = (*env)->GetObjectArrayElement(env, arr, i);
        if (objName == NULL) { (*env)->PopLocalFrame(env, NULL); continue; }

        /* ObjectName.toString() */
        jclass onClass = (*env)->GetObjectClass(env, objName);
        jmethodID toString = (*env)->GetMethodID(env, onClass, "toString",
            "()Ljava/lang/String;");
        jstring nameStr = (jstring)(*env)->CallObjectMethod(env, objName, toString);

        if (nameStr != NULL) {
            const char *nameChars = (*env)->GetStringUTFChars(env, nameStr, NULL);
            if (nameChars != NULL) {
                uint16_t nlen = (uint16_t)strlen(nameChars);
                if (nlen > 255) nlen = 255;
                if (off + 2 + nlen < JVMMON_MAX_PAYLOAD - 10) {
                    off += protocol_encode_string(payload + off, nameChars, nlen);
                }
                (*env)->ReleaseStringUTFChars(env, nameStr, nameChars);
            }
            (*env)->DeleteLocalRef(env, nameStr);
        }
        (*env)->PopLocalFrame(env, NULL);
    }

    agent_send_message(JVMMON_MSG_JMX_DATA, payload, (uint32_t)off);
    LOG_DEBUG("JMX: sent MBean list (%d beans)", (int)count);

cleanup:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, server);
}

static void send_platform_info_impl(jmx_reader_t *jr, JNIEnv *env) {
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;

    off += protocol_encode_u8(payload + off, JMX_SUBTYPE_PLATFORM_INFO);
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    /* ── OperatingSystemMXBean ─────────────── */
    jclass mf = (*env)->FindClass(env, "java/lang/management/ManagementFactory");
    if (mf != NULL) {
        /* OS info */
        jmethodID getOS = (*env)->GetStaticMethodID(env, mf,
            "getOperatingSystemMXBean",
            "()Ljava/lang/management/OperatingSystemMXBean;");
        if (getOS != NULL) {
            jobject osBean = (*env)->CallStaticObjectMethod(env, mf, getOS);
            if (osBean != NULL) {
                jclass osClass = (*env)->GetObjectClass(env, osBean);

                /* Available processors */
                jmethodID getProcs = (*env)->GetMethodID(env, osClass,
                    "getAvailableProcessors", "()I");
                if (getProcs != NULL) {
                    jint procs = (*env)->CallIntMethod(env, osBean, getProcs);
                    off += protocol_encode_string(payload + off, "os.availableProcessors", 22);
                    off += protocol_encode_i64(payload + off, (int64_t)procs);
                }

                /* System load average */
                jmethodID getLoad = (*env)->GetMethodID(env, osClass,
                    "getSystemLoadAverage", "()D");
                if (getLoad != NULL) {
                    jdouble load = (*env)->CallDoubleMethod(env, osBean, getLoad);
                    off += protocol_encode_string(payload + off, "os.systemLoadAverage", 20);
                    off += protocol_encode_i64(payload + off, (int64_t)(load * 1000));
                }

                /* Try com.sun.management for process CPU and system CPU */
                jmethodID getProcessCpu = (*env)->GetMethodID(env, osClass,
                    "getProcessCpuLoad", "()D");
                if (getProcessCpu != NULL) {
                    jdouble cpu = (*env)->CallDoubleMethod(env, osBean, getProcessCpu);
                    off += protocol_encode_string(payload + off, "os.processCpuLoad", 17);
                    off += protocol_encode_i64(payload + off, (int64_t)(cpu * 10000));
                } else {
                    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                }

                (*env)->DeleteLocalRef(env, osClass);
                (*env)->DeleteLocalRef(env, osBean);
            }
        }

        /* ── RuntimeMXBean ──────────────────── */
        jmethodID getRT = (*env)->GetStaticMethodID(env, mf,
            "getRuntimeMXBean", "()Ljava/lang/management/RuntimeMXBean;");
        if (getRT != NULL) {
            jobject rtBean = (*env)->CallStaticObjectMethod(env, mf, getRT);
            if (rtBean != NULL) {
                jclass rtClass = (*env)->GetObjectClass(env, rtBean);

                jmethodID getUptime = (*env)->GetMethodID(env, rtClass, "getUptime", "()J");
                if (getUptime != NULL) {
                    jlong uptime = (*env)->CallLongMethod(env, rtBean, getUptime);
                    off += protocol_encode_string(payload + off, "runtime.uptimeMs", 16);
                    off += protocol_encode_i64(payload + off, (int64_t)uptime);
                }

                jmethodID getVmName = (*env)->GetMethodID(env, rtClass, "getVmName",
                    "()Ljava/lang/String;");
                if (getVmName != NULL) {
                    jstring vmName = (jstring)(*env)->CallObjectMethod(env, rtBean, getVmName);
                    if (vmName != NULL) {
                        const char *s = (*env)->GetStringUTFChars(env, vmName, NULL);
                        if (s != NULL) {
                            off += protocol_encode_string(payload + off, "runtime.vmName", 14);
                            uint16_t slen = (uint16_t)strlen(s);
                            if (slen > 200) slen = 200;
                            off += protocol_encode_string(payload + off, s, slen);
                            (*env)->ReleaseStringUTFChars(env, vmName, s);
                        }
                        (*env)->DeleteLocalRef(env, vmName);
                    }
                }

                (*env)->DeleteLocalRef(env, rtClass);
                (*env)->DeleteLocalRef(env, rtBean);
            }
        }

        /* ── CompilationMXBean ──────────────── */
        jmethodID getComp = (*env)->GetStaticMethodID(env, mf,
            "getCompilationMXBean", "()Ljava/lang/management/CompilationMXBean;");
        if (getComp != NULL) {
            jobject compBean = (*env)->CallStaticObjectMethod(env, mf, getComp);
            if (compBean != NULL) {
                jclass compClass = (*env)->GetObjectClass(env, compBean);
                jmethodID getCompTime = (*env)->GetMethodID(env, compClass,
                    "getTotalCompilationTime", "()J");
                if (getCompTime != NULL) {
                    jlong compTime = (*env)->CallLongMethod(env, compBean, getCompTime);
                    off += protocol_encode_string(payload + off, "compilation.totalTimeMs", 23);
                    off += protocol_encode_i64(payload + off, (int64_t)compTime);
                }
                (*env)->DeleteLocalRef(env, compClass);
                (*env)->DeleteLocalRef(env, compBean);
            }
        }

        /* ── Memory Pool MBeans ─────────────── */
        jmethodID getMemPools = (*env)->GetStaticMethodID(env, mf,
            "getMemoryPoolMXBeans", "()Ljava/util/List;");
        if (getMemPools != NULL) {
            jobject poolList = (*env)->CallStaticObjectMethod(env, mf, getMemPools);
            if (poolList != NULL) {
                jclass listClass = (*env)->GetObjectClass(env, poolList);
                jmethodID listSize = (*env)->GetMethodID(env, listClass, "size", "()I");
                jmethodID listGet = (*env)->GetMethodID(env, listClass, "get",
                    "(I)Ljava/lang/Object;");
                jint poolCount = (*env)->CallIntMethod(env, poolList, listSize);

                jint p;
                for (p = 0; p < poolCount && off < JVMMON_MAX_PAYLOAD - 200; p++) {
                    jobject pool = (*env)->CallObjectMethod(env, poolList, listGet, p);
                    if (pool == NULL) continue;

                    jclass poolClass = (*env)->GetObjectClass(env, pool);
                    jmethodID getName = (*env)->GetMethodID(env, poolClass, "getName",
                        "()Ljava/lang/String;");
                    jmethodID getUsage = (*env)->GetMethodID(env, poolClass, "getUsage",
                        "()Ljava/lang/management/MemoryUsage;");

                    jstring poolName = (jstring)(*env)->CallObjectMethod(env, pool, getName);
                    jobject usage = (*env)->CallObjectMethod(env, pool, getUsage);

                    if (poolName != NULL && usage != NULL) {
                        const char *pname = (*env)->GetStringUTFChars(env, poolName, NULL);
                        if (pname != NULL) {
                            jclass usageClass = (*env)->GetObjectClass(env, usage);
                            jmethodID getUsed = (*env)->GetMethodID(env, usageClass,
                                "getUsed", "()J");
                            jmethodID getMax = (*env)->GetMethodID(env, usageClass,
                                "getMax", "()J");
                            jmethodID getComm = (*env)->GetMethodID(env, usageClass,
                                "getCommitted", "()J");

                            jlong used = (*env)->CallLongMethod(env, usage, getUsed);
                            jlong max = (*env)->CallLongMethod(env, usage, getMax);
                            jlong committed = (*env)->CallLongMethod(env, usage, getComm);

                            /* Encode: pool.<name>.used, pool.<name>.max, pool.<name>.committed */
                            char key[128];
                            snprintf(key, sizeof(key), "pool.%s.used", pname);
                            uint16_t klen = (uint16_t)strlen(key);
                            off += protocol_encode_string(payload + off, key, klen);
                            off += protocol_encode_i64(payload + off, (int64_t)used);

                            snprintf(key, sizeof(key), "pool.%s.max", pname);
                            klen = (uint16_t)strlen(key);
                            off += protocol_encode_string(payload + off, key, klen);
                            off += protocol_encode_i64(payload + off, (int64_t)max);

                            snprintf(key, sizeof(key), "pool.%s.committed", pname);
                            klen = (uint16_t)strlen(key);
                            off += protocol_encode_string(payload + off, key, klen);
                            off += protocol_encode_i64(payload + off, (int64_t)committed);

                            (*env)->ReleaseStringUTFChars(env, poolName, pname);
                            (*env)->DeleteLocalRef(env, usageClass);
                        }
                    }

                    if (poolName) (*env)->DeleteLocalRef(env, poolName);
                    if (usage) (*env)->DeleteLocalRef(env, usage);
                    (*env)->DeleteLocalRef(env, poolClass);
                    (*env)->DeleteLocalRef(env, pool);
                }
                (*env)->DeleteLocalRef(env, listClass);
                (*env)->DeleteLocalRef(env, poolList);
            }
        }

        (*env)->DeleteLocalRef(env, mf);
    }

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    /* Terminate with empty key */
    off += protocol_encode_u16(payload + off, 0);

    agent_send_message(JVMMON_MSG_JMX_DATA, payload, (uint32_t)off);
    LOG_DEBUG("JMX: sent platform info (%d bytes)", off);
}

static void send_subscribed_mbean(jmx_reader_t *jr, JNIEnv *env,
                                   const char *mbean_name) {
    jobject server = get_platform_mbean_server(env);
    if (server == NULL) return;

    /* Create ObjectName from string */
    jclass onClass = (*env)->FindClass(env, "javax/management/ObjectName");
    if (onClass == NULL) goto cleanup;
    jmethodID onCtor = (*env)->GetMethodID(env, onClass, "<init>", "(Ljava/lang/String;)V");
    if (onCtor == NULL) goto cleanup;
    jstring jname = (*env)->NewStringUTF(env, mbean_name);
    if (jname == NULL) goto cleanup;
    jobject objName = (*env)->NewObject(env, onClass, onCtor, jname);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, jname);
        goto cleanup;
    }

    /* server.getMBeanInfo(objName).getAttributes() */
    jclass serverClass = (*env)->GetObjectClass(env, server);
    jmethodID getInfo = (*env)->GetMethodID(env, serverClass, "getMBeanInfo",
        "(Ljavax/management/ObjectName;)Ljavax/management/MBeanInfo;");
    if (getInfo == NULL) goto cleanup;

    jobject mbeanInfo = (*env)->CallObjectMethod(env, server, getInfo, objName);
    if ((*env)->ExceptionCheck(env) || mbeanInfo == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        goto cleanup;
    }

    jclass infoClass = (*env)->GetObjectClass(env, mbeanInfo);
    jmethodID getAttrs = (*env)->GetMethodID(env, infoClass, "getAttributes",
        "()[Ljavax/management/MBeanAttributeInfo;");
    jobjectArray attrInfos = (jobjectArray)(*env)->CallObjectMethod(env, mbeanInfo, getAttrs);
    if (attrInfos == NULL) goto cleanup;

    jint attrCount = (*env)->GetArrayLength(env, attrInfos);

    /* Encode attributes */
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;
    off += protocol_encode_u8(payload + off, JMX_SUBTYPE_MBEAN_ATTRS);
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());

    uint16_t nameLen = (uint16_t)strlen(mbean_name);
    if (nameLen > 255) nameLen = 255;
    off += protocol_encode_string(payload + off, mbean_name, nameLen);

    /* getAttribute for each readable attribute */
    jmethodID getAttribute = (*env)->GetMethodID(env, serverClass, "getAttribute",
        "(Ljavax/management/ObjectName;Ljava/lang/String;)Ljava/lang/Object;");
    jclass attrInfoClass = (*env)->FindClass(env, "javax/management/MBeanAttributeInfo");
    jmethodID getAttrName = (*env)->GetMethodID(env, attrInfoClass, "getName",
        "()Ljava/lang/String;");
    jmethodID isReadable = (*env)->GetMethodID(env, attrInfoClass, "isReadable", "()Z");

    int sentAttrs = 0;
    jint ai;
    for (ai = 0; ai < attrCount && off < JVMMON_MAX_PAYLOAD - 100; ai++) {
        jobject attrInfo = (*env)->GetObjectArrayElement(env, attrInfos, ai);
        if (attrInfo == NULL) continue;

        jboolean readable = (*env)->CallBooleanMethod(env, attrInfo, isReadable);
        if (!readable) {
            (*env)->DeleteLocalRef(env, attrInfo);
            continue;
        }

        jstring attrNameStr = (jstring)(*env)->CallObjectMethod(env, attrInfo, getAttrName);
        if (attrNameStr == NULL) {
            (*env)->DeleteLocalRef(env, attrInfo);
            continue;
        }

        /* Try to get attribute value */
        jobject attrValue = (*env)->CallObjectMethod(env, server, getAttribute,
            objName, attrNameStr);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            (*env)->DeleteLocalRef(env, attrNameStr);
            (*env)->DeleteLocalRef(env, attrInfo);
            continue;
        }

        const char *aname = (*env)->GetStringUTFChars(env, attrNameStr, NULL);
        if (aname != NULL && attrValue != NULL) {
            /* Convert value to string via toString() */
            jclass valClass = (*env)->GetObjectClass(env, attrValue);
            jmethodID toString = (*env)->GetMethodID(env, valClass, "toString",
                "()Ljava/lang/String;");
            jstring valStr = (jstring)(*env)->CallObjectMethod(env, attrValue, toString);

            if (valStr != NULL) {
                const char *vchars = (*env)->GetStringUTFChars(env, valStr, NULL);
                if (vchars != NULL) {
                    uint16_t alen = (uint16_t)strlen(aname);
                    uint16_t vlen = (uint16_t)strlen(vchars);
                    if (alen > 200) alen = 200;
                    if (vlen > 500) vlen = 500;
                    if (off + 4 + alen + vlen < JVMMON_MAX_PAYLOAD - 10) {
                        off += protocol_encode_string(payload + off, aname, alen);
                        off += protocol_encode_string(payload + off, vchars, vlen);
                        sentAttrs++;
                    }
                    (*env)->ReleaseStringUTFChars(env, valStr, vchars);
                }
                (*env)->DeleteLocalRef(env, valStr);
            }
            (*env)->DeleteLocalRef(env, valClass);
            (*env)->ReleaseStringUTFChars(env, attrNameStr, aname);
        }

        if (attrValue) (*env)->DeleteLocalRef(env, attrValue);
        (*env)->DeleteLocalRef(env, attrNameStr);
        (*env)->DeleteLocalRef(env, attrInfo);
    }

    /* Terminate with empty key */
    off += protocol_encode_u16(payload + off, 0);

    agent_send_message(JVMMON_MSG_JMX_DATA, payload, (uint32_t)off);
    LOG_DEBUG("JMX: sent %d attrs for %s", sentAttrs, mbean_name);

cleanup:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, server);
}

/* ── Polling thread ─────────────────────────────────── */

static void *jmx_poll_thread_fn(void *arg) {
    jmx_reader_t *jr = (jmx_reader_t *)arg;
    JNIEnv *env;

    if ((*jr->agent->jvm)->AttachCurrentThread(jr->agent->jvm, (void **)&env, NULL) != JNI_OK) {
        LOG_ERROR("JMX reader: failed to attach thread to JVM");
        return NULL;
    }

    /* Send initial platform info and MBean list */
    jvmmon_sleep_ms(2000); /* wait for agent to fully start */
    if (jvmmon_atomic_load(&jr->running)) {
        send_platform_info_impl(jr, env);
        send_mbean_list_impl(jr, env);
    }

    while (jvmmon_atomic_load(&jr->running)) {
        /* Send platform info periodically */
        send_platform_info_impl(jr, env);

        /* Send subscribed MBean data */
        jvmmon_mutex_lock(&jr->lock);
        int i;
        for (i = 0; i < jr->subscribed_count; i++) {
            send_subscribed_mbean(jr, env, jr->subscribed[i]);
        }
        jvmmon_mutex_unlock(&jr->lock);

        jvmmon_sleep_ms(jr->interval_ms);
    }

    (*jr->agent->jvm)->DetachCurrentThread(jr->agent->jvm);
    return NULL;
}

/* ── Public API ─────────────────────────────────────── */

jmx_reader_t *jmx_reader_create(jvmmon_agent_t *agent, int interval_ms) {
    jmx_reader_t *jr = (jmx_reader_t *)jvmmon_calloc(1, sizeof(jmx_reader_t));
    if (jr == NULL) return NULL;
    jr->agent = agent;
    jr->interval_ms = interval_ms > 0 ? interval_ms : 5000;
    jr->running = 0;
    jr->subscribed_count = 0;
    jvmmon_mutex_init(&jr->lock);
    return jr;
}

int jmx_reader_start(jmx_reader_t *jr) {
    jvmmon_atomic_store(&jr->running, 1);
    return jvmmon_thread_create(&jr->poll_thread, jmx_poll_thread_fn, jr);
}

void jmx_reader_stop(jmx_reader_t *jr) {
    jvmmon_atomic_store(&jr->running, 0);
    jvmmon_thread_join(&jr->poll_thread);
}

void jmx_reader_destroy(jmx_reader_t *jr) {
    if (jr != NULL) {
        jvmmon_mutex_destroy(&jr->lock);
        jvmmon_free(jr);
    }
}

void jmx_reader_send_mbean_list(jmx_reader_t *jr) {
    JNIEnv *env;
    if ((*jr->agent->jvm)->GetEnv(jr->agent->jvm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        send_mbean_list_impl(jr, env);
    }
}

void jmx_reader_send_platform_info(jmx_reader_t *jr) {
    JNIEnv *env;
    if ((*jr->agent->jvm)->GetEnv(jr->agent->jvm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        send_platform_info_impl(jr, env);
    }
}

void jmx_reader_subscribe(jmx_reader_t *jr, const char *mbean_name) {
    jvmmon_mutex_lock(&jr->lock);
    if (jr->subscribed_count < 64) {
        /* Check for duplicate */
        int i;
        for (i = 0; i < jr->subscribed_count; i++) {
            if (strcmp(jr->subscribed[i], mbean_name) == 0) {
                jvmmon_mutex_unlock(&jr->lock);
                return;
            }
        }
        strncpy(jr->subscribed[jr->subscribed_count], mbean_name, 255);
        jr->subscribed_count++;
        LOG_INFO("JMX: subscribed to '%s'", mbean_name);
    }
    jvmmon_mutex_unlock(&jr->lock);
}

void jmx_reader_unsubscribe(jmx_reader_t *jr, const char *mbean_name) {
    jvmmon_mutex_lock(&jr->lock);
    int i;
    for (i = 0; i < jr->subscribed_count; i++) {
        if (strcmp(jr->subscribed[i], mbean_name) == 0) {
            /* Shift remaining */
            int j;
            for (j = i; j < jr->subscribed_count - 1; j++) {
                strncpy(jr->subscribed[j], jr->subscribed[j + 1], 255);
            }
            jr->subscribed_count--;
            LOG_INFO("JMX: unsubscribed from '%s'", mbean_name);
            break;
        }
    }
    jvmmon_mutex_unlock(&jr->lock);
}
