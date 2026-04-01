/*
 * JVMMonitor - Class Histogram Implementation (Fixed)
 * Uses JVMTI heap callbacks to count live objects per class.
 * WARNING: causes GC-like pause. Use sparingly.
 *
 * Approach: Tag each class with a unique ID, then use FollowReferences
 * with a heap callback that counts objects by class tag.
 * Simpler approach used here: IterateOverHeap with class tag counting.
 */
#include "jvmmon/class_histo.h"
#include "jvmmon/log.h"
#include "jvmmon/protocol.h"
#include <string.h>
#include <stdlib.h>

#define MAX_HISTO_CLASSES 4096

/* Global state for heap callback (JVMTI callbacks can't carry user data in older APIs) */
static volatile int32_t g_histo_counts[MAX_HISTO_CLASSES];
static volatile int64_t g_histo_sizes[MAX_HISTO_CLASSES];

static jvmtiIterationControl JNICALL
heap_object_cb(jlong class_tag, jlong size, jlong *tag_ptr, void *user_data) {
    (void)tag_ptr;
    (void)user_data;
    if (class_tag > 0 && class_tag <= MAX_HISTO_CLASSES) {
        int idx = (int)(class_tag - 1);
        g_histo_counts[idx]++;
        g_histo_sizes[idx] += size;
    }
    return JVMTI_ITERATION_CONTINUE;
}

typedef struct {
    char    name[200];
    int32_t count;
    int64_t total_size;
} histo_result_t;

static int histo_cmp_count_desc(const void *a, const void *b) {
    const histo_result_t *ea = (const histo_result_t *)a;
    const histo_result_t *eb = (const histo_result_t *)b;
    if (eb->count > ea->count) return 1;
    if (eb->count < ea->count) return -1;
    return 0;
}

class_histo_t *class_histo_create(jvmmon_agent_t *agent) {
    class_histo_t *ch = (class_histo_t *)jvmmon_calloc(1, sizeof(class_histo_t));
    if (ch == NULL) return NULL;
    ch->agent = agent;
    return ch;
}

void class_histo_destroy(class_histo_t *ch) {
    if (ch != NULL) jvmmon_free(ch);
}

int class_histo_activate(int level, const char *target, void *ctx) {
    class_histo_t *ch = (class_histo_t *)ctx;
    (void)level; (void)target;
    class_histo_snapshot(ch);
    return 0;
}

int class_histo_deactivate(int level, void *ctx) {
    (void)level; (void)ctx;
    return 0;
}

void class_histo_snapshot(class_histo_t *ch) {
    jvmtiEnv *jvmti = ch->agent->jvmti;
    jclass *classes = NULL;
    jint class_count = 0;
    jvmtiError err;

    LOG_INFO("Class histogram: starting (will pause application briefly)");
    uint64_t start = jvmmon_time_nanos();

    err = (*jvmti)->GetLoadedClasses(jvmti, &class_count, &classes);
    if (err != JVMTI_ERROR_NONE || classes == NULL) {
        LOG_ERROR("Class histogram: GetLoadedClasses failed (%d)", (int)err);
        return;
    }

    /* Tag each class with its index (1-based) */
    int tag_count = class_count < MAX_HISTO_CLASSES ? (int)class_count : MAX_HISTO_CLASSES;
    char **class_names = (char **)jvmmon_calloc((size_t)tag_count, sizeof(char *));
    if (class_names == NULL) {
        (*jvmti)->Deallocate(jvmti, (unsigned char *)classes);
        return;
    }

    int i;
    for (i = 0; i < tag_count; i++) {
        (*jvmti)->SetTag(jvmti, classes[i], (jlong)(i + 1));
        (*jvmti)->GetClassSignature(jvmti, classes[i], &class_names[i], NULL);
    }

    /* Reset counters */
    memset((void *)g_histo_counts, 0, sizeof(g_histo_counts));
    memset((void *)g_histo_sizes, 0, sizeof(g_histo_sizes));

    /* Walk the heap — this is the STW part */
    err = (*jvmti)->IterateOverHeap(jvmti, JVMTI_HEAP_OBJECT_EITHER,
                                     heap_object_cb, NULL);
    if (err != JVMTI_ERROR_NONE) {
        LOG_ERROR("Class histogram: IterateOverHeap failed (%d)", (int)err);
    }

    uint64_t elapsed_ns = jvmmon_time_nanos() - start;

    /* Build sorted results */
    histo_result_t *results = (histo_result_t *)jvmmon_calloc((size_t)tag_count, sizeof(histo_result_t));
    int result_count = 0;

    if (results != NULL) {
        for (i = 0; i < tag_count; i++) {
            if (g_histo_counts[i] > 0) {
                strncpy(results[result_count].name,
                        class_names[i] ? class_names[i] : "?", 199);
                results[result_count].count = g_histo_counts[i];
                results[result_count].total_size = g_histo_sizes[i];
                result_count++;
            }
        }
        qsort(results, (size_t)result_count, sizeof(histo_result_t), histo_cmp_count_desc);
    }

    /* Encode top 100 */
    uint8_t payload[JVMMON_MAX_PAYLOAD];
    int off = 0;
    off += protocol_encode_u64(payload + off, jvmmon_time_millis());
    off += protocol_encode_u64(payload + off, elapsed_ns);
    int send_count = result_count < 100 ? result_count : 100;
    off += protocol_encode_u16(payload + off, (uint16_t)send_count);

    for (i = 0; i < send_count && off < (int)(JVMMON_MAX_PAYLOAD - 50); i++) {
        uint16_t nlen = (uint16_t)strlen(results[i].name);
        if (nlen > 200) nlen = 200;
        off += protocol_encode_string(payload + off, results[i].name, nlen);
        off += protocol_encode_i32(payload + off, results[i].count);
        off += protocol_encode_i64(payload + off, results[i].total_size);
    }

    agent_send_message(JVMMON_MSG_CLASS_HISTO, payload, (uint32_t)off);

    LOG_INFO("Class histogram: %d classes with instances, top=%s (%d), took %.1fms",
             result_count,
             result_count > 0 ? results[0].name : "none",
             result_count > 0 ? results[0].count : 0,
             elapsed_ns / 1000000.0);

    /* Cleanup: clear tags */
    for (i = 0; i < tag_count; i++) {
        (*jvmti)->SetTag(jvmti, classes[i], 0);
        if (class_names[i]) (*jvmti)->Deallocate(jvmti, (unsigned char *)class_names[i]);
    }

    jvmmon_free(class_names);
    if (results) jvmmon_free(results);
    (*jvmti)->Deallocate(jvmti, (unsigned char *)classes);
}
