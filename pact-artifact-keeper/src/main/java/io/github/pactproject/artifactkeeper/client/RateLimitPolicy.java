package io.github.pactproject.artifactkeeper.client;

import java.math.BigInteger;
import java.net.http.HttpHeaders;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;

/** One logical request includes every HTTP attempt and all rate-limit waits. */
final class RateLimitPolicy {
    static final int MAX_RETRIES = 3;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_WAIT_MILLIS = 5_000;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final Duration timeout;

    RateLimitPolicy() { this(Clock.systemUTC(), System::nanoTime, Thread::sleep, REQUEST_TIMEOUT); }
    RateLimitPolicy(Clock clock, LongSupplier nanoTime, Sleeper sleeper, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Request timeout must be positive");
        this.clock = clock; this.nanoTime = nanoTime; this.sleeper = sleeper; this.timeout = timeout;
    }

    long start() { return nanoTime.getAsLong(); }
    Duration remaining(long start) throws ArtifactKeeperClientException {
        long remaining = timeout.toNanos() - (nanoTime.getAsLong() - start);
        if (remaining <= 0) throw new ArtifactKeeperClientException("Artifact Keeper logical request timeout exhausted");
        return Duration.ofNanos(remaining);
    }

    long delayMillis(HttpHeaders headers) throws ArtifactKeeperClientException {
        String retry = headers.firstValue("Retry-After").orElse(null);
        if (retry != null) {
            if (retry.matches("[0-9]+")) return bounded(new BigInteger(retry).multiply(BigInteger.valueOf(1000)));
            Instant retryAt = null;
            try { retryAt = ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            catch (java.time.format.DateTimeParseException ignored) {
                try { retryAt = Instant.parse(retry); }
                catch (java.time.format.DateTimeParseException ignoredAgain) { }
            }
            if (retryAt != null) return bounded(BigInteger.valueOf(retryAt.getEpochSecond()).multiply(BigInteger.valueOf(1000))
                    .add(BigInteger.valueOf(retryAt.getNano() / 1_000_000)).subtract(BigInteger.valueOf(clock.millis())));
        }
        String reset = headers.firstValue("X-RateLimit-Reset").orElse(null);
        if (reset != null && reset.matches("[0-9]+")) return bounded(new BigInteger(reset)
                .multiply(BigInteger.valueOf(1000)).subtract(BigInteger.valueOf(clock.millis())));
        return 1_000;
    }

    private long bounded(BigInteger delay) throws ArtifactKeeperClientException {
        if (delay.compareTo(BigInteger.valueOf(MAX_WAIT_MILLIS)) > 0) {
            // Do not retry earlier than the server allowed just to meet our wait bound.
            throw new ArtifactKeeperClientException("Artifact Keeper HTTP 429 requires a wait exceeding 5000 ms; retry aborted");
        }
        return delay.max(BigInteger.ZERO).longValueExact();
    }

    void pause(long started, long millis) throws ArtifactKeeperClientException {
        if (Duration.ofMillis(millis).compareTo(remaining(started)) >= 0) {
            throw new ArtifactKeeperClientException("Artifact Keeper rate-limit wait exceeds remaining logical request timeout");
        }
        try { sleeper.sleep(millis); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArtifactKeeperClientException("Interrupted while waiting for Artifact Keeper rate limit", e);
        }
        remaining(started);
    }

    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
}
