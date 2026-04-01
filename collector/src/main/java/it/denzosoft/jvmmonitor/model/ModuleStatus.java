package it.denzosoft.jvmmonitor.model;

public final class ModuleStatus {

    private final String name;
    private int currentLevel;
    private int maxLevel;
    private String statusMessage;
    private double estimatedOverhead;

    public ModuleStatus(String name, int currentLevel, int maxLevel) {
        this.name = name;
        this.currentLevel = currentLevel;
        this.maxLevel = maxLevel;
        this.statusMessage = "";
        this.estimatedOverhead = 0.0;
    }

    public String getName() { return name; }
    public int getCurrentLevel() { return currentLevel; }
    public int getMaxLevel() { return maxLevel; }
    public String getStatusMessage() { return statusMessage; }
    public double getEstimatedOverhead() { return estimatedOverhead; }

    public void setCurrentLevel(int level) { this.currentLevel = level; }
    public void setMaxLevel(int level) { this.maxLevel = level; }
    public void setStatusMessage(String msg) { this.statusMessage = msg; }
    public void setEstimatedOverhead(double overhead) { this.estimatedOverhead = overhead; }

    public boolean isActive() { return currentLevel > 0; }
}
