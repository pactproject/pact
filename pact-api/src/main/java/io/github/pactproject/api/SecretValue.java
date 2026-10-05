package io.github.pactproject.api;

import java.util.Objects;

public final class SecretValue {
    private final String value;

    public SecretValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Secret value must not be null");
        }
        this.value = value;
    }

    public String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SecretValue secret
                && value.equals(secret.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
