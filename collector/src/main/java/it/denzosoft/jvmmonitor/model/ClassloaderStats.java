package it.denzosoft.jvmmonitor.model;

public final class ClassloaderStats {

    private final long timestamp;
    private final LoaderInfo[] loaders;

    public ClassloaderStats(long timestamp, LoaderInfo[] loaders) {
        this.timestamp = timestamp;
        this.loaders = loaders;
    }

    public long getTimestamp() { return timestamp; }
    public LoaderInfo[] getLoaders() { return loaders; }
    public int getLoaderCount() { return loaders != null ? loaders.length : 0; }

    public int getTotalClassCount() {
        int total = 0;
        if (loaders != null) {
            for (int i = 0; i < loaders.length; i++) {
                total += loaders[i].classCount;
            }
        }
        return total;
    }

    public static final class LoaderInfo {
        private final String loaderClass;
        private final int classCount;

        public LoaderInfo(String loaderClass, int classCount) {
            this.loaderClass = loaderClass;
            this.classCount = classCount;
        }

        public String getLoaderClass() { return loaderClass; }
        public int getClassCount() { return classCount; }
    }
}
