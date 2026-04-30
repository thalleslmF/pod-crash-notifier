package io.crashnotifier.crd;

import java.util.ArrayList;
import java.util.List;

public class PodWatcherStatus {
    private List<AlertedPod> alertedPods = new ArrayList<>();

    public List<AlertedPod> getAlertedPods() { return alertedPods; }
    public void setAlertedPods(List<AlertedPod> alertedPods) { this.alertedPods = alertedPods; }
}
