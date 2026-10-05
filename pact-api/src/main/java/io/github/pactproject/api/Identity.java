package io.github.pactproject.api;

import java.util.Objects;

public final class Identity {
    private final String backendId;
    private final String principal;
    private final boolean ensure;
    private final String passwordSource;
    private final String passwordVersion;
    private final SecretValue password;

    public Identity(
            String backendId,
            String principal,
            boolean ensure,
            String passwordSource,
            String passwordVersion,
            SecretValue password
    ) {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException(
                    "Identity backend id must not be blank"
            );
        }
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException(
                    "Identity principal must not be blank"
            );
        }
        if (passwordSource == null
                && (passwordVersion != null || password != null)) {
            throw new IllegalArgumentException(
                    "Password version and value require a password source"
            );
        }
        if (password != null
                && (passwordVersion == null || passwordVersion.isBlank())) {
            throw new IllegalArgumentException(
                    "Resolved password requires a version"
            );
        }
        this.backendId = backendId;
        this.principal = principal;
        this.ensure = ensure;
        this.passwordSource = passwordSource;
        this.passwordVersion = passwordVersion;
        this.password = password;
    }

    public String backendId() {
        return backendId;
    }

    public String principal() {
        return principal;
    }

    public boolean ensure() {
        return ensure;
    }

    public String passwordSource() {
        return passwordSource;
    }

    public String passwordVersion() {
        return passwordVersion;
    }

    public SecretValue password() {
        return password;
    }

    public Identity withPassword(
            String source,
            String version,
            SecretValue value
    ) {
        if (passwordSource == null) {
            throw new IllegalStateException(
                    "Identity has no password Secret reference"
            );
        }
        return new Identity(
                backendId,
                principal,
                ensure,
                source,
                version,
                value
        );
    }

    public Identity withPasswordVersion(String source, String version) {
        if (passwordSource == null) {
            throw new IllegalStateException(
                    "Identity has no password Secret reference"
            );
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException(
                    "Password version must not be blank"
            );
        }
        return new Identity(
                backendId,
                principal,
                ensure,
                source,
                version,
                null
        );
    }

    public Identity withoutPassword() {
        return password == null
                ? this
                : new Identity(
                        backendId,
                        principal,
                        ensure,
                        passwordSource,
                        passwordVersion,
                        null
                );
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Identity identity)) {
            return false;
        }
        return ensure == identity.ensure
                && backendId.equals(identity.backendId)
                && principal.equals(identity.principal)
                && Objects.equals(passwordSource, identity.passwordSource)
                && Objects.equals(passwordVersion, identity.passwordVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                backendId,
                principal,
                ensure,
                passwordSource,
                passwordVersion
        );
    }

    @Override
    public String toString() {
        return "Identity[backendId=" + backendId
                + ", principal=" + principal
                + ", ensure=" + ensure
                + ", passwordSource=" + passwordSource
                + ", passwordVersion=" + passwordVersion
                + ", password=" + (password == null ? "null" : "[REDACTED]")
                + "]";
    }
}
