package io.crashnotifier.crd;

import java.time.Instant;

public class AlertedPod {
    private String podName;
    private String reason;
    private String alertedAt;

    public AlertedPod() {}
    public AlertedPod(String podName, String reason) {
        this.podName = podName;
        this.reason = reason;
        this.alertedAt = Instant.now().toString();
    }

    public String getPodName() { return podName; }
    public void setPodName(String podName) { this.podName = podName; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getAlertedAt() { return alertedAt; }
    public void setAlertedAt(String alertedAt) { this.alertedAt = alertedAt; }
}
