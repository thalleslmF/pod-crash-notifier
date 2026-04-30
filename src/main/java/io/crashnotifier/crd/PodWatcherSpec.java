package io.crashnotifier.crd;

public class PodWatcherSpec {
    private WebhookConfig webhook;
    private DetectionConfig detections = new DetectionConfig();
    private String resetCron = "0 0 * * *";

    public WebhookConfig getWebhook() { return webhook; }
    public void setWebhook(WebhookConfig webhook) { this.webhook = webhook; }
    public DetectionConfig getDetections() { return detections; }
    public void setDetections(DetectionConfig detections) { this.detections = detections; }
    public String getResetCron() { return resetCron; }
    public void setResetCron(String resetCron) { this.resetCron = resetCron; }
}
