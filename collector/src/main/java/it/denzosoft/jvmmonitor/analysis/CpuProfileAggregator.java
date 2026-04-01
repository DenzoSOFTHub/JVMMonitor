package it.denzosoft.jvmmonitor.analysis;

import it.denzosoft.jvmmonitor.model.CpuSample;

import java.util.*;

/**
 * Aggregates CPU samples into a call tree and hot method list.
 * Each sample contributes to method counts: "self" for the top frame,
 * "total" for all frames in the stack.
 */
public class CpuProfileAggregator {

    private final Map<String, MethodNode> methodMap = new LinkedHashMap<String, MethodNode>();
    private int totalSamples = 0;

    public void aggregate(List<CpuSample> samples) {
        methodMap.clear();
        totalSamples = samples.size();

        for (int s = 0; s < samples.size(); s++) {
            CpuSample sample = samples.get(s);
            CpuSample.StackFrame[] frames = sample.getFrames();
            if (frames == null || frames.length == 0) continue;

            /* Top frame = self */
            String topKey = frameKey(frames[0]);
            getOrCreate(topKey, frames[0]).selfCount++;

            /* All frames = total (deduplicate within this sample) */
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < frames.length; i++) {
                String key = frameKey(frames[i]);
                if (seen.add(key)) {
                    getOrCreate(key, frames[i]).totalCount++;
                }
            }

            /* Build parent-child edges (top = callee, deeper = caller) */
            for (int i = 0; i < frames.length - 1; i++) {
                String childKey = frameKey(frames[i]);
                String parentKey = frameKey(frames[i + 1]);
                MethodNode parent = getOrCreate(parentKey, frames[i + 1]);
                MethodNode child = getOrCreate(childKey, frames[i]);
                int[] edge = parent.children.get(childKey);
                if (edge == null) {
                    edge = new int[]{0};
                    parent.children.put(childKey, edge);
                }
                edge[0]++;
                child.callerKeys.add(parentKey);
            }
        }
    }

    /** Get top methods sorted by self sample count (descending). */
    public List<MethodNode> getTopMethods(int limit) {
        List<MethodNode> sorted = new ArrayList<MethodNode>(methodMap.values());
        Collections.sort(sorted, new Comparator<MethodNode>() {
            public int compare(MethodNode a, MethodNode b) {
                return b.selfCount - a.selfCount;
            }
        });
        if (sorted.size() > limit) sorted = sorted.subList(0, limit);
        return sorted;
    }

    /** Get root nodes (methods that appear as the deepest frame). */
    public List<MethodNode> getRootNodes(int limit) {
        List<MethodNode> sorted = new ArrayList<MethodNode>(methodMap.values());
        Collections.sort(sorted, new Comparator<MethodNode>() {
            public int compare(MethodNode a, MethodNode b) {
                return b.totalCount - a.totalCount;
            }
        });
        if (sorted.size() > limit) sorted = sorted.subList(0, limit);
        return sorted;
    }

    public int getTotalSamples() {
        return totalSamples;
    }

    public MethodNode getNode(String key) {
        return methodMap.get(key);
    }

    private MethodNode getOrCreate(String key, CpuSample.StackFrame frame) {
        MethodNode node = methodMap.get(key);
        if (node == null) {
            node = new MethodNode(key, frame.getDisplayName());
            methodMap.put(key, node);
        }
        return node;
    }

    private static String frameKey(CpuSample.StackFrame f) {
        if (f.getClassName() != null && f.getMethodName() != null) {
            return f.getClassName() + "." + f.getMethodName();
        }
        return "method@0x" + Long.toHexString(f.getMethodId());
    }

    public static class MethodNode {
        public final String key;
        public final String displayName;
        public int selfCount;
        public int totalCount;
        /** childKey -> edge sample count */
        public final Map<String, int[]> children = new LinkedHashMap<String, int[]>();
        public final Set<String> callerKeys = new LinkedHashSet<String>();

        public MethodNode(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        public double selfPercent(int totalSamples) {
            return totalSamples > 0 ? selfCount * 100.0 / totalSamples : 0;
        }

        public double totalPercent(int totalSamples) {
            return totalSamples > 0 ? totalCount * 100.0 / totalSamples : 0;
        }
    }
}
