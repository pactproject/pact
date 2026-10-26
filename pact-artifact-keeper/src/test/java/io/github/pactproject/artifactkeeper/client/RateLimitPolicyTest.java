package io.github.pactproject.artifactkeeper.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.http.HttpHeaders;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RateLimitPolicyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);
    private static HttpHeaders headers(String key, String value) {
        return HttpHeaders.of(Map.of(key, List.of(value)), (name, item) -> true);
    }
    private static RateLimitPolicy policy() { return new RateLimitPolicy(CLOCK, () -> 0, millis -> fail("No real waits"), Duration.ofSeconds(30)); }

    @ParameterizedTest
    @ValueSource(strings = {"2", "Sun, 25 Oct 2026 12:00:02 GMT", "2026-10-25T12:00:02Z"})
    void interpretsRetryAfterWithoutRealWaits(String value) throws Exception {
        assertEquals(2000, policy().delayMillis(headers("Retry-After", value)));
    }

    @Test void handlesResetPastAndMalformedHeaders() throws Exception {
        assertEquals(3000, policy().delayMillis(headers("X-RateLimit-Reset", "" + (CLOCK.instant().getEpochSecond() + 3))));
        assertEquals(0, policy().delayMillis(headers("Retry-After", "2026-10-25T11:59:00Z")));
        assertEquals(1000, policy().delayMillis(headers("Retry-After", "invalid")));
        assertEquals(1000, policy().delayMillis(headers("Retry-After", "-1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"6", "9223372036854775807", "99999999999999999999999999999", "+999999999-01-01T00:00:00Z"})
    void rejectsExcessiveServerWaitRatherThanSleepingOrRetryingTooEarly(String value) {
        assertThrows(ArtifactKeeperClientException.class, () -> policy().delayMillis(headers("Retry-After", value)));
        assertThrows(ArtifactKeeperClientException.class, () -> policy().delayMillis(headers("X-RateLimit-Reset", "99999999999999999999999999999")));
    }

    @Test void attemptsAndWaitsShareOneBudget() throws Exception {
        var nanos = new AtomicLong();
        var waits = new ArrayList<Long>();
        var policy = new RateLimitPolicy(CLOCK, nanos::get, millis -> { waits.add(millis); nanos.addAndGet(millis * 1_000_000); }, Duration.ofSeconds(1));
        long started = policy.start();
        policy.pause(started, 300);
        assertEquals(Duration.ofMillis(700), policy.remaining(started));
        assertThrows(ArtifactKeeperClientException.class, () -> policy.pause(started, 700));
        assertEquals(List.of(300L), waits);
        nanos.set(1_000_000_000);
        assertThrows(ArtifactKeeperClientException.class, () -> policy.remaining(started));
    }

    @Test void interruptedWaitPreservesThreadInterruptAndFailsChecked() {
        var policy = new RateLimitPolicy(CLOCK, () -> 0, millis -> { throw new InterruptedException(); }, Duration.ofSeconds(30));
        try {
            assertThrows(ArtifactKeeperClientException.class, () -> policy.pause(0, 1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
