package it.denzosoft.jvmmonitor.agent.module;

/** Interface for all collector modules. */
public interface Module {
    String getName();
    int getMaxLevel();
    boolean isCore(); /* Core modules are always active */
    void activate(int level);
    void deactivate();
    boolean isActive();
}
