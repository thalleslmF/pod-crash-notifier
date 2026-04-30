package io.crashnotifier.crd;

public class DetectionConfig {
    private boolean crashLoopBackOff = true;
    private int restartThreshold = 5;
    private boolean healthCheckFailing = true;
    private boolean unschedulable = true;
    private boolean imagePullError = true;
    private boolean oomKilled = true;
    private boolean createContainerError = true;
    private boolean initContainerFailing = true;
    private int pendingThresholdSeconds = 120;

    public boolean isCrashLoopBackOff() { return crashLoopBackOff; }
    public void setCrashLoopBackOff(boolean v) { this.crashLoopBackOff = v; }
    public int getRestartThreshold() { return restartThreshold; }
    public void setRestartThreshold(int v) { this.restartThreshold = v; }
    public boolean isHealthCheckFailing() { return healthCheckFailing; }
    public void setHealthCheckFailing(boolean v) { this.healthCheckFailing = v; }
    public boolean isUnschedulable() { return unschedulable; }
    public void setUnschedulable(boolean v) { this.unschedulable = v; }
    public boolean isImagePullError() { return imagePullError; }
    public void setImagePullError(boolean v) { this.imagePullError = v; }
    public boolean isOomKilled() { return oomKilled; }
    public void setOomKilled(boolean v) { this.oomKilled = v; }
    public boolean isCreateContainerError() { return createContainerError; }
    public void setCreateContainerError(boolean v) { this.createContainerError = v; }
    public boolean isInitContainerFailing() { return initContainerFailing; }
    public void setInitContainerFailing(boolean v) { this.initContainerFailing = v; }
    public int getPendingThresholdSeconds() { return pendingThresholdSeconds; }
    public void setPendingThresholdSeconds(int v) { this.pendingThresholdSeconds = v; }
}
