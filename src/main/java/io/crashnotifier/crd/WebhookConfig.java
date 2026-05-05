package io.crashnotifier.crd;

import java.util.List;

public class WebhookConfig {
    private String url;
    private List<WebhookHeader> headers;
    private String bodyTemplate;
    private AuthConfig auth;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public List<WebhookHeader> getHeaders() { return headers; }
    public void setHeaders(List<WebhookHeader> headers) { this.headers = headers; }
    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }
    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }
}
