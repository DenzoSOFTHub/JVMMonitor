package it.denzosoft.jvmmonitor.analysis;

import it.denzosoft.jvmmonitor.model.ClassHistogram;

import java.util.*;

/**
 * Analyzes class histogram snapshots to identify:
 * - Large long-lived objects (old generation residents)
 * - Classes with growing instance counts (potential leaks)
 * - Heap composition breakdown
 *
 * Logic: objects that persist across multiple histogram snapshots (taken after GC)
 * are in old gen. Objects whose count grows consistently are leak suspects.
 */
public class HeapAnalyzer {

    /**
     * Compare the oldest and newest histograms to find:
     * - Classes present in both = old gen residents (survived across snapshots)
     * - Classes whose count/size grew = leak suspects
     * - Classes with largest total size = top memory consumers
     */
    public static List<OldGenEntry> analyzeOldGen(List<ClassHistogram> histograms) {
        if (histograms == null || histograms.isEmpty()) {
            return Collections.emptyList();
        }

        ClassHistogram latest = histograms.get(histograms.size() - 1);
        ClassHistogram oldest = histograms.get(0);

        /* Build map of oldest snapshot */
        Map<String, ClassHistogram.Entry> oldMap = new LinkedHashMap<String, ClassHistogram.Entry>();
        if (oldest.getEntries() != null) {
            for (int i = 0; i < oldest.getEntries().length; i++) {
                ClassHistogram.Entry e = oldest.getEntries()[i];
                if (e != null) oldMap.put(e.getClassName(), e);
            }
        }

        /* Count how many snapshots each class appears in (survival count) */
        Map<String, int[]> survivalCount = new LinkedHashMap<String, int[]>();
        for (int h = 0; h < histograms.size(); h++) {
            ClassHistogram histo = histograms.get(h);
            if (histo.getEntries() == null) continue;
            for (int i = 0; i < histo.getEntries().length; i++) {
                ClassHistogram.Entry e = histo.getEntries()[i];
                if (e == null) continue;
                int[] cnt = survivalCount.get(e.getClassName());
                if (cnt == null) { cnt = new int[]{0}; survivalCount.put(e.getClassName(), cnt); }
                cnt[0]++;
            }
        }

        /* Build analysis results from latest snapshot */
        List<OldGenEntry> results = new ArrayList<OldGenEntry>();
        if (latest.getEntries() != null) {
            for (int i = 0; i < latest.getEntries().length; i++) {
                ClassHistogram.Entry curr = latest.getEntries()[i];
                if (curr == null) continue;

                ClassHistogram.Entry old = oldMap.get(curr.getClassName());
                int survived = survivalCount.containsKey(curr.getClassName())
                        ? survivalCount.get(curr.getClassName())[0] : 1;

                int countDelta = 0;
                long sizeDelta = 0;
                if (old != null) {
                    countDelta = curr.getInstanceCount() - old.getInstanceCount();
                    sizeDelta = curr.getTotalSize() - old.getTotalSize();
                }

                boolean isOldGen = survived >= Math.max(2, histograms.size() / 2);
                boolean isGrowing = countDelta > 0 && old != null;

                results.add(new OldGenEntry(
                        curr.getClassName(),
                        curr.getInstanceCount(),
                        curr.getTotalSize(),
                        countDelta,
                        sizeDelta,
                        survived,
                        histograms.size(),
                        isOldGen,
                        isGrowing));
            }
        }

        /* Sort by total size descending */
        Collections.sort(results, new Comparator<OldGenEntry>() {
            public int compare(OldGenEntry a, OldGenEntry b) {
                return Long.compare(b.totalSize, a.totalSize);
            }
        });

        return results;
    }

    /** Get only growing classes (leak suspects), sorted by size growth. */
    public static List<OldGenEntry> getLeakSuspects(List<OldGenEntry> entries) {
        List<OldGenEntry> suspects = new ArrayList<OldGenEntry>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).isGrowing && entries.get(i).isOldGen) {
                suspects.add(entries.get(i));
            }
        }
        Collections.sort(suspects, new Comparator<OldGenEntry>() {
            public int compare(OldGenEntry a, OldGenEntry b) {
                return Long.compare(b.sizeDelta, a.sizeDelta);
            }
        });
        return suspects;
    }

    public static class OldGenEntry {
        public final String className;
        public final int instanceCount;
        public final long totalSize;
        public final int countDelta;       /* change since first snapshot */
        public final long sizeDelta;       /* size change since first snapshot */
        public final int survivedSnapshots;
        public final int totalSnapshots;
        public final boolean isOldGen;     /* present in most snapshots */
        public final boolean isGrowing;    /* instance count increasing */

        public OldGenEntry(String className, int instanceCount, long totalSize,
                           int countDelta, long sizeDelta,
                           int survivedSnapshots, int totalSnapshots,
                           boolean isOldGen, boolean isGrowing) {
            this.className = className;
            this.instanceCount = instanceCount;
            this.totalSize = totalSize;
            this.countDelta = countDelta;
            this.sizeDelta = sizeDelta;
            this.survivedSnapshots = survivedSnapshots;
            this.totalSnapshots = totalSnapshots;
            this.isOldGen = isOldGen;
            this.isGrowing = isGrowing;
        }

        public double getTotalSizeMB() { return totalSize / (1024.0 * 1024.0); }
        public double getSizeDeltaMB() { return sizeDelta / (1024.0 * 1024.0); }

        public String getSurvivalLabel() {
            return survivedSnapshots + "/" + totalSnapshots;
        }

        public String getDisplayClassName() {
            return it.denzosoft.jvmmonitor.gui.chart.ClassNameFormatter.format(className);
        }
    }
}
