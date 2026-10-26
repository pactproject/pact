package io.github.pactproject.artifactkeeper.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pactproject.artifactkeeper.ArtifactKeeperApiProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactKeeperRetryTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void retriesOnlyBoundedRateLimitsAndStopsAfterExhaustion(boolean eventualSuccess) throws Exception {
        var calls = new AtomicInteger();
        var nanos = new AtomicLong();
        var waits = new ArrayList<Long>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int attempt = calls.incrementAndGet();
            byte[] bytes = "{\"items\":[],\"pagination\":{\"total_pages\":0,\"total\":0}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After", "1");
            exchange.sendResponseHeaders(eventualSuccess && attempt == 4 ? 200 : 429, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var policy = new RateLimitPolicy(Clock.systemUTC(), nanos::get,
                    millis -> { waits.add(millis); nanos.addAndGet(millis * 1_000_000); }, Duration.ofSeconds(30));
            var client = client(server, policy);
            if (eventualSuccess) assertTrue(client.getUsers().isEmpty());
            else assertThrows(ArtifactKeeperClientException.class, client::getUsers);
            assertEquals(4, calls.get());
            assertEquals(List.of(1000L, 1000L, 1000L), waits);
        } finally { server.stop(0); }
    }

    @Test void refusesLongWaitAndDoesNotRetryAnIndeterminateWrite() throws Exception {
        var calls = new AtomicInteger();
        var loseResponse = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (!loseResponse.get()) {
                exchange.getResponseHeaders().set("Retry-After", "999999999999999999999");
                exchange.sendResponseHeaders(429, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            var policy = new RateLimitPolicy(Clock.systemUTC(), System::nanoTime, millis -> fail("Must not wait"), Duration.ofSeconds(30));
            var client = client(server, policy);
            assertThrows(ArtifactKeeperClientException.class, client::getUsers);
            assertEquals(1, calls.get());
            loseResponse.set(true);
            assertThrows(ArtifactKeeperClientException.class, () -> client.createServiceAccount("test"));
            assertEquals(2, calls.get(), "A lost POST response must not trigger a blind retry");
        } finally { server.stop(0); }
    }

    @Test void requestDeadlineCancelsBlockedResponse() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var client = client(server, new RateLimitPolicy(Clock.systemUTC(), System::nanoTime,
                    millis -> fail("Must not wait"), Duration.ofMillis(200)));
            var error = assertThrows(ArtifactKeeperClientException.class, client::getUsers);
            assertTrue(error.getMessage().contains("Timeout"));
        } finally { release.countDown(); server.stop(0); }
    }

    private static ArtifactKeeperHttpClient client(HttpServer server, RateLimitPolicy policy) {
        return new ArtifactKeeperHttpClient(HttpClient.newHttpClient(), new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-token", 1, ArtifactKeeperApiProfile.CURRENT, policy);
    }
}
