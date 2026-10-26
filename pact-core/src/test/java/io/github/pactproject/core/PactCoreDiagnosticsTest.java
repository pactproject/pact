package io.github.pactproject.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.github.pactproject.api.*;
import io.github.pactproject.api.exception.*;
import io.github.pactproject.core.exception.ApplyException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PactCoreDiagnosticsTest {
    @Test void correlatesPreparationApplicationAndCompensationWithoutLoggingPassword() {
        var logger = (Logger) LoggerFactory.getLogger(PactCore.class);
        var events = new ListAppender<ILoggingEvent>();
        var oldLevel = logger.getLevel();
        events.start(); logger.addAppender(events); logger.setLevel(Level.DEBUG);
        try {
            Backend backend = new Backend() {
                public String id() { return "packages"; }
                public void validateIdentities(Set<Identity> identities) { }
                public void apply(PactState state) throws BackendException {
                    if (!state.accesses().isEmpty()) throw new BackendOperationException("Synthetic write failure");
                }
            };
            var desired = new PactState(Set.of(new Access("alice", new Resource("packages", Map.of("repository", "releases")), Map.of())),
                    Set.of(new Identity("packages", "alice", true, "secret/password", "1", new SecretValue("not-for-logs"))));
            assertThrows(ApplyException.class, () -> new PactCore(List.of(backend)).apply(desired));
            var phases = events.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("reconciliation=")).toList();
            String correlation = phases.getFirst().split(" ")[0];
            assertTrue(phases.stream().allMatch(message -> message.startsWith(correlation + " ")));
            for (String expected : List.of("phase=prepare result=completed", "phase=apply result=failed", "phase=rollback result=completed")) {
                assertTrue(phases.stream().anyMatch(message -> message.contains("backend=packages")
                        && message.contains(expected) && message.contains("duration_ms=")), expected);
            }
            assertTrue(phases.stream().noneMatch(message -> message.contains("phase=apply result=completed")));
            String output = events.list.stream().map(event -> event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(output.contains("not-for-logs"));
        } finally { logger.detachAppender(events); events.stop(); logger.setLevel(oldLevel); }
    }
}
