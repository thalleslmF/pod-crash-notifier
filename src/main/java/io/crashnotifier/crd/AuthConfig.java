package io.crashnotifier.crd;

public class AuthConfig {
    private String refreshUrl;
    private String refreshBody;
    private String tokenJsonPath;
    private String refreshTokenJsonPath;
    private String token;
    private String refreshToken;

    public String getRefreshUrl() { return refreshUrl; }
    public void setRefreshUrl(String refreshUrl) { this.refreshUrl = refreshUrl; }
    public String getRefreshBody() { return refreshBody; }
    public void setRefreshBody(String refreshBody) { this.refreshBody = refreshBody; }
    public String getTokenJsonPath() { return tokenJsonPath; }
    public void setTokenJsonPath(String tokenJsonPath) { this.tokenJsonPath = tokenJsonPath; }
    public String getRefreshTokenJsonPath() { return refreshTokenJsonPath; }
    public void setRefreshTokenJsonPath(String refreshTokenJsonPath) { this.refreshTokenJsonPath = refreshTokenJsonPath; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
}
