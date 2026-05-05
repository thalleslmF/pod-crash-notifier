package io.crashnotifier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crashnotifier.crd.AuthConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenRefresherTest {

    private final TokenRefresher refresher = new TokenRefresher();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resolveAuthPlaceholders_replacesTokenAndRefreshToken() {
        AuthConfig auth = new AuthConfig();
        auth.setToken("access123");
        auth.setRefreshToken("refresh456");

        String template = "{\"token\": \"${auth.token}\", \"refreshToken\": \"${auth.refreshToken}\"}";
        String result = refresher.resolveAuthPlaceholders(template, auth);

        assertEquals("{\"token\": \"access123\", \"refreshToken\": \"refresh456\"}", result);
    }

    @Test
    void resolveAuthPlaceholders_nullTemplate_returnsEmpty() {
        AuthConfig auth = new AuthConfig();
        assertEquals("", refresher.resolveAuthPlaceholders(null, auth));
    }

    @Test
    void resolveAuthPlaceholders_noPlaceholders_returnsUnchanged() {
        AuthConfig auth = new AuthConfig();
        auth.setToken("t");
        String template = "{\"fixed\": \"value\"}";
        assertEquals(template, refresher.resolveAuthPlaceholders(template, auth));
    }

    @Test
    void extractJsonPath_simpleField() throws Exception {
        JsonNode root = mapper.readTree("{\"token\": \"abc123\"}");
        assertEquals("abc123", refresher.extractJsonPath(root, "$.token"));
    }

    @Test
    void extractJsonPath_nestedField() throws Exception {
        JsonNode root = mapper.readTree("{\"data\": {\"accessToken\": \"nested-value\"}}");
        assertEquals("nested-value", refresher.extractJsonPath(root, "$.data.accessToken"));
    }

    @Test
    void extractJsonPath_missingField_returnsNull() throws Exception {
        JsonNode root = mapper.readTree("{\"other\": \"value\"}");
        assertNull(refresher.extractJsonPath(root, "$.token"));
    }

    @Test
    void extractJsonPath_nullPath_returnsNull() throws Exception {
        JsonNode root = mapper.readTree("{\"token\": \"abc\"}");
        assertNull(refresher.extractJsonPath(root, null));
    }

    @Test
    void extractJsonPath_withoutDollarPrefix() throws Exception {
        JsonNode root = mapper.readTree("{\"token\": \"abc\"}");
        assertEquals("abc", refresher.extractJsonPath(root, "token"));
    }

    @Test
    void refresh_nullAuth_returnsEmpty() {
        assertTrue(refresher.refresh(null).isEmpty());
    }

    @Test
    void refresh_noRefreshUrl_returnsEmpty() {
        AuthConfig auth = new AuthConfig();
        assertTrue(refresher.refresh(auth).isEmpty());
    }
}
