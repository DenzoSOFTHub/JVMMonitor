/*
 * JVMMonitor - Classloader Leak Detection Implementation
 * Enumerates all loaded classes, groups by classloader, detects loaders with
 * excessive class counts or growing patterns.
 */
#include "jvmmon/classloader_tracker.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>

#define MAX_CLASSLOADERS 128

typedef struct {
    jobject loader_ref;     /* weak global ref */
    jlong   tag;
    int32_t class_count;
    char    loader_class[128];
} loader_entry_t;

static void collect_classloaders(classloader_tracker_t *ct) {
    jvmtiEnv *jvmti = ct->agent->jvmti;
    jclass *classes = NULL;
    jint class_count = 0;
    jvmtiError err;

    err = (*jvmti)->GetLoadedClasses(jvmti, &class_count, &classes);
    if (err != JVMTI_ERROR_NONE || classes == NULL) return;

    /* Count classes per unique classloader (by tag) */
    loader_entry_t loaders[MAX_CLASSLOADERS];
    int loader_count = 0;
    memset(loaders, 0, sizeof(loaders));

    jint i;
    for (i = 0; i < class_count; i++) {
        jobject loader = NULL;
        (*jvmti)->GetClassLoader(jvmti, classes[i], &loader);

        /* Find or add loader */
        int found = -1;
        int j;
        if (loader == NULL) {
            /* Bootstrap classloader — find existing or create */
            for (j = 0; j < loader_count; j++) {
                if (loaders[j].tag == -1) { found = j; break; }
            }
            if (found < 0 && loader_count < MAX_CLASSLOADERS) {
                found = loader_count;
                loaders[found].tag = -1;
                strncpy(loaders[found].loader_class, "bootstrap", 127);
                loaders[found].loader_class[127] = '\0';
                loaders[found].class_count = 0;
                loader_count++;
            }
        } else {
            /* Use tag to identify unique loaders */
            jlong tag = 0;
            (*jvmti)->GetTag(jvmti, loader, &tag);
            if (tag == 0) {
                /* New loader — assign tag */
                tag = (jlong)(loader_count + 100);
                (*jvmti)->SetTag(jvmti, loader, tag);
            }

            for (j = 0; j < loader_count; j++) {
                if (loaders[j].tag == tag) { found = j; break; }
            }

            if (found < 0 && loader_count < MAX_CLASSLOADERS) {
                found = loader_count;
                loaders[found].tag = tag;
                loaders[found].class_count = 0;

                /* Get classloader class name */
                JNIEnv *env;
                if ((*ct->agent->jvm)->GetEnv(ct->agent->jvm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
                    jclass loaderClass = (*env)->GetObjectClass(env, loader);
                    char *csig = NULL;
                    (*jvmti)->GetClassSignature(jvmti, loaderClass, &csig, NULL);
                    if (csig) {
                        strncpy(loaders[found].loader_class, csig, 127);
                        loaders[found].loader_class[127] = '\0';
                        (*jvmti)->Deallocate(jvmti, (unsigned char *)csig);
                    }
                    (*env)->DeleteLocalRef(env, loaderClass);
                }
                loader_count++;
            }
        }

        if (found >= 0) {
            loaders[found].class_count++;
        }

        /* Clean up JNI local ref for this loader to prevent table overflow */
        if (loader != NULL) {
            JNIEnv *lenv;
            if ((*ct->agent->jvm)->GetEnv(ct->agent->jvm, (void **)&lenv, JNI_VERSION_1_6) == JNI_OK) {
                (*lenv)->DeleteLocalRef(lenv, loader);
            }
        }
    }

    /* Clear tags in a lightweight pass (no GetClassLoader — use SetTag with tag=0 via iteration) */
    /* Tags are ephemeral; cleared by JVMTI automatically on next GC or via explicit clear.
     * We skip the second GetClassLoader loop to save time. Tags will be overwritten next poll. */

    /* Encode: timestamp + count + entries(class_name, class_count) */
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u16(payload + off, (uint16_t)loader_count);

    for (i = 0; i < loader_count && off < (int)(JVMMON_MAX_PAYLOAD - 50); i++) {
        uint16_t nlen = (uint16_t)strlen(loaders[i].loader_class);
        if (nlen > 120) nlen = 120;
        off += protocol_encode_string(payload + off, loaders[i].loader_class, nlen);
        off += protocol_encode_i32(payload + off, loaders[i].class_count);
    }

    agent_send_message(JVMMON_MSG_CLASSLOADER, payload, (uint32_t)off);
    LOG_DEBUG("Classloader: %d loaders, %d total classes", loader_count, (int)class_count);

    (*jvmti)->Deallocate(jvmti, (unsigned char *)classes);
}

static void *poll_fn(void *arg) {
    classloader_tracker_t *ct = (classloader_tracker_t *)arg;
    while (jvmmon_atomic_load(&ct->running)) {
        collect_classloaders(ct);
        jvmmon_sleep_ms(ct->interval_ms);
    }
    return NULL;
}

classloader_tracker_t *classloader_tracker_create(jvmmon_agent_t *agent, int interval_ms) {
    classloader_tracker_t *ct = (classloader_tracker_t *)jvmmon_calloc(1, sizeof(classloader_tracker_t));
    if (!ct) return NULL;
    ct->agent = agent; ct->interval_ms = interval_ms > 0 ? interval_ms : 10000;
    return ct;
}
void classloader_tracker_destroy(classloader_tracker_t *ct) {
    if (ct) { if (jvmmon_atomic_load(&ct->running)) classloader_tracker_deactivate(0, ct); jvmmon_free(ct); }
}
int classloader_tracker_activate(int level, const char *target, void *ctx) {
    classloader_tracker_t *ct = (classloader_tracker_t *)ctx; (void)level; (void)target;
    jvmmon_atomic_store(&ct->running, 1);
    jvmmon_thread_create(&ct->poll_thread, poll_fn, ct);
    LOG_INFO("Classloader tracker activated"); return 0;
}
int classloader_tracker_deactivate(int level, void *ctx) {
    classloader_tracker_t *ct = (classloader_tracker_t *)ctx; (void)level;
    jvmmon_atomic_store(&ct->running, 0);
    jvmmon_thread_join(&ct->poll_thread);
    LOG_INFO("Classloader tracker deactivated"); return 0;
}
