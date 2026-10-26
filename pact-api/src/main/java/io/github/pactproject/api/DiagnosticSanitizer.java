package io.github.pactproject.api;

import java.util.Comparator;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Retains operational details while masking authentication material in diagnostics. */
public final class DiagnosticSanitizer {
    private static final String FIELDS = "password|passwd|pwd|token|access_token|refresh_token|api[_-]?key|authorization";
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:" + FIELDS + ")\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(\\bAuthorization\\s*:\\s*)(?:Basic|Bearer|ApiKey)\\s+[^\\s,;]+");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(\\b(?:" + FIELDS + ")\\s*[:=]\\s*)(?:\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|[^\\s,;&]+)");
    private static final Pattern URI_PASSWORD = Pattern.compile("(://[^/@\\s:]+:)[^/@\\s]+@");

    private DiagnosticSanitizer() { }

    /** Pass known connection credentials and passwords from the current write, including encoded auth headers. */
    public static String redact(String text, String... secrets) {
        if (text == null) return "";
        var values = new TreeSet<String>(Comparator.comparingInt(String::length).reversed()
                .thenComparing(Comparator.naturalOrder()));
        for (String secret : secrets) {
            if (secret == null || secret.isEmpty()) continue;
            values.add(secret);
            values.add(jsonEscaped(secret));
        }
        String result = text;
        for (String value : values) result = result.replace(value, "[REDACTED]");
        result = JSON_SECRET.matcher(result).replaceAll("$1\"[REDACTED]\"");
        result = AUTHORIZATION.matcher(result).replaceAll("$1[REDACTED]");
        result = NAMED_SECRET.matcher(result).replaceAll("$1[REDACTED]");
        return URI_PASSWORD.matcher(result).replaceAll("$1[REDACTED]@");
    }

    private static String jsonEscaped(String value) {
        var out = new StringBuilder();
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (ch < 0x20) out.append("\\u%04x".formatted((int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.toString();
    }
}
