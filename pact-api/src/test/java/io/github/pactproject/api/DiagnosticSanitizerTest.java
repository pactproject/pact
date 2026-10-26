package io.github.pactproject.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticSanitizerTest {
    @Test
    void retainsUsersResourcesPermissionsAndServerReason() {
        String details = "alice denied SELECT on analytics.public.events; policy=readers status=403";
        assertEquals(details, DiagnosticSanitizer.redact(details, "connection-password"));
    }

    @Test
    void masksKnownRawAndJsonEscapedSecretsIncludingOverlappingValues() {
        String secret = "quoted\"password\\with\nline";
        String escaped = "quoted\\\"password\\\\with\\nline";
        String result = DiagnosticSanitizer.redact("reason=" + secret + " " + escaped + " abcdef abc",
                secret, "abc", "abcdef", null, "");
        assertFalse(result.contains("quoted"));
        assertFalse(result.contains("abc"));
        assertTrue(result.contains("reason="));
    }

    @Test
    void masksNamedSecretsAndAuthorizationWithoutHidingAclData() {
        String result = DiagnosticSanitizer.redact("""
                {"user":"alice","password":"unknown\\\"value","api_key":"unknown-key",
                 "nested":{"token":"unknown-token"},"permissions":["read"]}
                Authorization: Basic unknown-encoded-value
                password=unknown-plain; endpoint=https://alice:unknown-uri@host/api
                """);
        assertFalse(result.contains("unknown"));
        assertTrue(result.contains("alice"));
        assertTrue(result.contains("permissions"));
        assertTrue(result.contains("read"));
        assertTrue(result.contains("host/api"));
    }
}
