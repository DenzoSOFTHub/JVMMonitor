package it.denzosoft.jvmmonitor.model;

public final class Diagnosis {

    private final long timestamp;
    private final String category;
    private final int severity;
    private final String location;
    private final String summary;
    private final String evidence;
    private final String fix;
    private final String estimatedImpact;
    private final String suggestedAction;

    private Diagnosis(Builder b) {
        this.timestamp = b.timestamp;
        this.category = b.category;
        this.severity = b.severity;
        this.location = b.location;
        this.summary = b.summary;
        this.evidence = b.evidence;
        this.fix = b.fix;
        this.estimatedImpact = b.estimatedImpact;
        this.suggestedAction = b.suggestedAction;
    }

    public long getTimestamp() { return timestamp; }
    public String getCategory() { return category; }
    public int getSeverity() { return severity; }
    public String getLocation() { return location; }
    public String getSummary() { return summary; }
    public String getEvidence() { return evidence; }
    public String getFix() { return fix; }
    public String getEstimatedImpact() { return estimatedImpact; }
    public String getSuggestedAction() { return suggestedAction; }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        String sev = severity == 2 ? "CRITICAL" : severity == 1 ? "WARNING" : "INFO";
        sb.append("--- ").append(sev).append(" --- ").append(category).append(" ---\n");
        if (location != null) sb.append("WHERE:    ").append(location).append('\n');
        if (summary != null) sb.append("PROBLEM:  ").append(summary).append('\n');
        if (evidence != null) sb.append("EVIDENCE: ").append(evidence).append('\n');
        if (fix != null) sb.append("FIX:      ").append(fix).append('\n');
        if (estimatedImpact != null) sb.append("IMPACT:   ").append(estimatedImpact).append('\n');
        if (suggestedAction != null) sb.append("ACTION:   ").append(suggestedAction).append('\n');
        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long timestamp;
        private String category;
        private int severity;
        private String location;
        private String summary;
        private String evidence;
        private String fix;
        private String estimatedImpact;
        private String suggestedAction;

        public Builder timestamp(long ts) { this.timestamp = ts; return this; }
        public Builder category(String c) { this.category = c; return this; }
        public Builder severity(int s) { this.severity = s; return this; }
        public Builder location(String l) { this.location = l; return this; }
        public Builder summary(String s) { this.summary = s; return this; }
        public Builder evidence(String e) { this.evidence = e; return this; }
        public Builder fix(String f) { this.fix = f; return this; }
        public Builder estimatedImpact(String i) { this.estimatedImpact = i; return this; }
        public Builder suggestedAction(String a) { this.suggestedAction = a; return this; }
        public Diagnosis build() { return new Diagnosis(this); }
    }
}
