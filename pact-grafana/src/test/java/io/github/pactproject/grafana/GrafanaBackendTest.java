package io.github.pactproject.grafana;

import io.github.pactproject.api.*;
import io.github.pactproject.api.exception.*;
import io.github.pactproject.core.PactCore;
import io.github.pactproject.grafana.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.pactproject.grafana.GrafanaCompilerTest.*;
import static io.github.pactproject.grafana.model.GrafanaResource.Kind.*;

class GrafanaBackendTest {
    @Test void namedScopeFollowsCurrentFolderNameAcrossTransitionsAndRemoval() throws Exception {
        var client = new FakeGrafanaClient();
        var original = client.add(FOLDER, "original", "Reports", "");
        var previous = state(access("alice", Map.of("folder", "Reports"), "View"));
        try (var core = new PactCore(List.of(new GrafanaBackend("monitoring", client)))) {
            core.apply(previous);
            client.objects.set(0, new GrafanaObject(original, "Archive", ""));
            var current = client.add(FOLDER, "replacement", "Reports", "");
            client.acls.put(current, List.of(GrafanaPermission.user(20, 4)));
            core.apply(state(access("alice", Map.of("folder", "Reports"), "Edit")));
            assertEquals(List.of(GrafanaPermission.user(10, 1)), client.acls.get(original));
            assertEquals(List.of(GrafanaPermission.user(10, 2)), client.acls.get(current));
            core.apply(PactState.empty());
            assertTrue(client.acls.get(current).isEmpty());
            assertEquals(List.of(GrafanaPermission.user(10, 1)), client.acls.get(original));
        }
    }

    @Test void restoredNamedScopeCleansCurrentNameWithoutBindingOldUid() throws Exception {
        var client = new FakeGrafanaClient();
        var original = client.add(FOLDER, "original", "Reports", "");
        var previous = state(access("alice", Map.of("folder", "Reports"), "View"));
        new GrafanaBackend("monitoring", client).apply(previous);
        client.objects.set(0, new GrafanaObject(original, "Archive", ""));
        var current = client.add(FOLDER, "replacement", "Reports", "");
        client.acls.put(current, List.of(GrafanaPermission.user(20, 4)));
        try (var restarted = new PactCore(List.of(new GrafanaBackend("monitoring", client)))) {
            restarted.restoreAppliedState(previous);
            restarted.apply(PactState.empty());
            assertTrue(client.acls.get(current).isEmpty());
            assertEquals(List.of(GrafanaPermission.user(10, 1)), client.acls.get(original));
        }
    }

    @Test void missingPreviousNamesAllowRemovalWhileMissingDesiredNamesStillFail() throws Exception {
        var client = new FakeGrafanaClient();
        var original = client.add(FOLDER, "original", "Reports", "");
        var previous = state(access("alice", Map.of("folder", "Reports"), "View"));
        var backend = new GrafanaBackend("monitoring", client);
        backend.apply(previous);
        client.objects.set(0, new GrafanaObject(original, "Archive", ""));
        int writes = client.writes;
        backend.prepareTransaction(previous, PactState.empty(), Set.of(previous)).apply();
        assertEquals(writes, client.writes);
        assertEquals(List.of(GrafanaPermission.user(10, 1)), client.acls.get(original));
        assertThrows(ValidationException.class, () -> backend.apply(previous));
        assertEquals(writes, client.writes);
        // A UID selector still follows the original object through a rename.
        backend.prepareTransaction(state(access("alice", Map.of("folderUid", "original"), "View")), PactState.empty()).apply();
        assertTrue(client.acls.get(original).isEmpty());
    }

    @Test void renamedOrMovedDashboardsLeavePreviousCombinedSelectorsAndAmbiguityStillFails() throws Exception {
        var client = new FakeGrafanaClient();
        client.add(FOLDER, "reports", "Reports", "");
        client.add(FOLDER, "archive", "Archive", "");
        var dashboard = client.add(DASHBOARD, "dashboard", "CPU", "reports");
        var backend = new GrafanaBackend("monitoring", client);
        var named = state(access("alice", Map.of("folder", "Reports", "dashboard", "CPU"), "View"));
        backend.apply(named);
        client.objects.set(2, new GrafanaObject(dashboard, "Renamed", "reports"));
        int writes = client.writes;
        backend.prepareTransaction(named, PactState.empty()).apply();
        assertEquals(writes, client.writes);
        assertThrows(ValidationException.class, () -> backend.apply(named));
        var constrained = state(access("alice", Map.of("folderUid", "reports", "dashboardUid", "dashboard"), "View"));
        client.objects.set(2, new GrafanaObject(dashboard, "Renamed", "archive"));
        backend.prepareTransaction(constrained, PactState.empty()).apply();
        assertEquals(writes, client.writes);
        assertThrows(ValidationException.class, () -> backend.apply(constrained));
        var parentNamed = state(access("alice", Map.of("folder", "Reports", "dashboardUid", "dashboard"), "View"));
        client.objects.remove(0);
        backend.prepareTransaction(parentNamed, PactState.empty()).apply();
        assertEquals(writes, client.writes);
        client.add(FOLDER, "another-archive", "Archive", "");
        var ambiguous = state(access("alice", Map.of("folder", "Archive"), "View"));
        assertThrows(ValidationException.class, () -> backend.prepareTransaction(ambiguous, PactState.empty()));
        assertEquals(writes, client.writes);
        assertEquals(List.of(GrafanaPermission.user(10, 1)), client.acls.get(dashboard));
    }

    @Test void removedObjectsAndUsersDoNotBlockCleanupAndRootDashboardsResolveExactly() throws Exception {
        var client = new FakeGrafanaClient();
        var root = client.add(DASHBOARD, "root-dash", "Root", "");
        var backend = new GrafanaBackend("monitoring", client);
        backend.apply(state(access("alice", Map.of("folder", "/", "dashboard", "Root"), "View")));
        assertEquals(List.of(GrafanaPermission.user(10, 1)), client.acls.get(root));
        var previous = state(access("alice", Map.of("dashboardUid", "root-dash"), "View"),
                access("alice", Map.of("folderUid", "already-deleted"), "Edit"));
        client.users.removeFirst();
        backend.prepareTransaction(previous, PactState.empty()).apply();
        assertTrue(client.acls.get(root).isEmpty());
        var empty = new FakeGrafanaClient();
        new GrafanaBackend("monitoring", empty).apply(PactState.empty());
        assertEquals(0, empty.writes);
    }

    @Test void nativeDiagnosticsDistinguishLostResponsesFromConfirmedWrites() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(io.github.pactproject.grafana.sync.GrafanaSynchronizer.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var oldLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            var client = new FakeGrafanaClient();
            client.add(FOLDER, "f", "F", "");
            client.lostResponses.add(1);
            var backend = new GrafanaBackend("monitoring", client);
            assertThrows(BackendOperationException.class, () -> backend.apply(state(access("alice", Map.of("folderUid", "f"), "View"))));
            String logs = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(logs.contains("backend=monitoring action=replace_acl result=started"));
            assertTrue(logs.contains("result=failed confirmed_completed=0 planned=1"));
            assertFalse(logs.contains("action=replace_acl result=completed"));
            assertTrue(logs.contains("uid=f"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(oldLevel);
            appender.stop();
        }
    }

    @Test void resolvesNamesAndUidsAggregatesAndReconcilesWholeAclIdempotently() throws Exception {
        var client = new FakeGrafanaClient();
        var parent = client.add(FOLDER, "root", "Kubernetes", "");
        var child = client.add(FOLDER, "prod", "Production", "root");
        var dashboard = client.add(DASHBOARD, "cpu", "CPU / Usage", "prod");
        var unrelated = client.add(DASHBOARD, "other", "CPU / Usage", "root");
        client.acls.put(dashboard, List.of(new GrafanaPermission(GrafanaPermission.Subject.ROLE, "Viewer", 1),
                new GrafanaPermission(GrafanaPermission.Subject.TEAM, "50", 4), GrafanaPermission.user(20, 2)));
        client.acls.put(unrelated, List.of(GrafanaPermission.user(20, 4)));
        var backend = new GrafanaBackend("monitoring", client);
        var named = state(access("alice", Map.of("folder", "Kubernetes/Production", "dashboard", "CPU / Usage"), "View"),
                access("alice", Map.of("dashboardUid", "cpu"), "Edit"), access("bob", Map.of("folderUid", "prod"), "Admin"));
        var transaction = backend.prepareTransaction(PactState.empty(), named);
        assertEquals(0, client.writes, "preparation must be read-only");
        transaction.apply();
        assertEquals(List.of(GrafanaPermission.user(10, 2)), client.acls.get(dashboard));
        assertEquals(List.of(GrafanaPermission.user(20, 4)), client.acls.get(child));
        assertEquals(List.of(GrafanaPermission.user(20, 4)), client.acls.get(unrelated));
        assertTrue(client.acls.get(parent).isEmpty());
        int writes = client.writes;
        var uid = state(access("alice", Map.of("dashboardUid", "cpu"), "Edit"), access("bob", Map.of("folderUid", "prod"), "Admin"));
        backend.apply(uid);
        backend.prepareTransaction(named, uid).apply();
        assertEquals(writes, client.writes);
        backend.prepareTransaction(uid, PactState.empty()).apply();
        assertTrue(client.acls.get(dashboard).isEmpty());
        assertTrue(client.acls.get(child).isEmpty());
        assertEquals(List.of(GrafanaPermission.user(20, 4)), client.acls.get(unrelated));
        assertEquals(2, client.users.size());
    }

    @Test void validatesEveryReferenceAndEveryAclBeforeWrites() throws Exception {
        var client = new FakeGrafanaClient();
        var first = client.add(FOLDER, "a", "A", "");
        var later = client.add(FOLDER, "z", "Z", "");
        var backend = new GrafanaBackend("monitoring", client);
        var desired = state(access("alice", Map.of("folderUid", "a"), "View"), access("bob", Map.of("folderUid", "z"), "Edit"));
        client.failedRead = later;
        assertThrows(BackendOperationException.class, () -> backend.prepareTransaction(PactState.empty(), desired));
        assertEquals(0, client.writes);
        client.failedRead = null;
        var transaction = backend.prepareTransaction(PactState.empty(), desired);
        client.failedRead = later;
        assertThrows(BackendOperationException.class, transaction::apply);
        assertEquals(0, client.writes);
        client.failedRead = null;
        var missing = assertThrows(ValidationException.class, () -> backend.apply(state(access("new-user", Map.of("folderUid", "a"), "View"))));
        assertTrue(missing.getMessage().contains("first sign in"));
        assertThrows(ValidationException.class, () -> backend.apply(state(access("alice", Map.of("dashboardUid", "absent"), "View"))));
        client.users.add(new GrafanaUser(30, "alice"));
        assertThrows(ValidationException.class, () -> backend.apply(desired));
        assertEquals(0, client.writes);
        assertTrue(client.acls.get(first).isEmpty());
    }

    @Test void rejectsAmbiguousPathsWrongParentTypesAndIncompleteOrCyclicCatalogs() throws Exception {
        var client = new FakeGrafanaClient();
        client.add(FOLDER, "a", "Same", "");
        client.add(FOLDER, "b", "Same", "");
        client.add(DASHBOARD, "d", "Title", "a");
        var backend = new GrafanaBackend("monitoring", client);
        assertThrows(ValidationException.class, () -> backend.apply(state(access("alice", Map.of("folder", "Same"), "View"))));
        assertThrows(ValidationException.class, () -> backend.apply(state(access("alice", Map.of("folderUid", "b", "dashboardUid", "d"), "View"))));
        assertThrows(ValidationException.class, () -> backend.apply(state(access("alice", Map.of("folderUid", "d"), "View"))));
        client.add(DASHBOARD, "duplicate-title", "Title", "a");
        assertThrows(ValidationException.class, () -> backend.apply(state(access("alice", Map.of("folderUid", "a", "dashboard", "Title"), "View"))));
        client.add(FOLDER, "orphan", "Orphan", "missing");
        client.add(FOLDER, "ancestor-first", "Child", "orphan");
        assertThrows(ValidationException.class, () -> backend.apply(state(access("alice", Map.of("folderUid", "a"), "View"))));
        client.objects.removeLast();
        client.objects.removeLast();
        client.add(FOLDER, "cycle", "Cycle", "cycle");
        assertThrows(ValidationException.class, () -> backend.apply(PactState.empty()));
        assertEquals(0, client.writes);
    }

    @Test void detectsReferenceChangesBetweenPreparationAndApplicationAndRefreshesCatalogs() throws Exception {
        var client = new FakeGrafanaClient();
        client.add(FOLDER, "a", "A", "");
        var backend = new GrafanaBackend("monitoring", client);
        var desired = state(access("alice", Map.of("folder", "A"), "View"));
        var transaction = backend.prepareTransaction(PactState.empty(), desired);
        client.users.set(0, new GrafanaUser(99, "alice"));
        assertThrows(ValidationException.class, transaction::apply);
        assertEquals(0, client.writes);
        backend.apply(desired);
        assertEquals(List.of(GrafanaPermission.user(99, 1)), client.acls.get(client.objects.getFirst().resource()));
        var newFolder = client.add(FOLDER, "new", "New", "");
        backend.apply(state(access("alice", Map.of("folder", "New"), "Edit")));
        assertEquals(List.of(GrafanaPermission.user(99, 2)), client.acls.get(newFolder));
    }

    @Test void compensatesLostResponsesToPreviousDeclarationAcrossAttemptedScopesAndRetries() throws Exception {
        var client = new FakeGrafanaClient();
        var old = client.add(FOLDER, "a", "Old", "");
        var attempted = client.add(FOLDER, "z", "New", "");
        var backend = new GrafanaBackend("monitoring", client);
        var previous = state(access("alice", Map.of("folderUid", "a"), "View"));
        backend.apply(previous);
        client.acls.put(old, List.of(GrafanaPermission.user(10, 1), GrafanaPermission.user(20, 4)));
        var desired = state(access("alice", Map.of("folderUid", "z"), "Edit"));
        var transaction = backend.prepareTransaction(previous, desired);
        client.lostResponses.add(client.writes + 2);
        assertThrows(BackendOperationException.class, transaction::apply);
        assertEquals(List.of(GrafanaPermission.user(10, 2)), client.acls.get(attempted));
        transaction.rollback();
        assertEquals(List.of(GrafanaPermission.user(10, 1)), client.acls.get(old), "manual drift is not the rollback target");
        assertTrue(client.acls.get(attempted).isEmpty());
        backend.prepareTransaction(previous, desired).apply();
        assertTrue(client.acls.get(old).isEmpty());
        assertEquals(List.of(GrafanaPermission.user(10, 2)), client.acls.get(attempted));
    }

    @Test void retainsUncertainScopesInCoreAndRecoversToLatestEmptyDeclaration() throws Exception {
        var client = new FakeGrafanaClient();
        var old = client.add(FOLDER, "a", "Old", "");
        var attempted = client.add(FOLDER, "z", "New", "");
        var backend = new GrafanaBackend("monitoring", client);
        try (var core = new PactCore(List.of(backend))) {
            core.apply(state(access("alice", Map.of("folderUid", "a"), "View")));
            // Apply clears A and writes Z (lost response); rollback fails after restoring A, leaving Z uncertain.
            client.lostResponses.addAll(Set.of(3, 4));
            assertThrows(Exception.class, () -> core.apply(state(access("bob", Map.of("folderUid", "z"), "Admin"))));
            assertFalse(client.acls.get(attempted).isEmpty());
            core.apply(PactState.empty());
            assertTrue(client.acls.get(old).isEmpty());
            assertTrue(client.acls.get(attempted).isEmpty());
            int writes = client.writes;
            core.apply(PactState.empty());
            assertEquals(writes, client.writes);
        }
        assertEquals(1, client.closes);
        assertEquals(2, client.users.size());
    }

    @Test void recoveryScopesNeverBecomeDesiredGrantsAndTransactionsRemainIndependent() throws Exception {
        var client = new FakeGrafanaClient();
        var old = client.add(FOLDER, "a", "A", "");
        var attempted = client.add(FOLDER, "b", "B", "");
        var unrelated = client.add(FOLDER, "c", "C", "");
        var backend = new GrafanaBackend("monitoring", client);
        var previous = state(access("alice", Map.of("folderUid", "a"), "View"));
        var recovery = state(access("bob", Map.of("folderUid", "b"), "Admin"));
        client.acls.put(attempted, List.of(GrafanaPermission.user(20, 4)));
        var transaction = backend.prepareTransaction(previous, previous, Set.of(recovery));
        var other = backend.prepareTransaction(PactState.empty(), state(access("bob", Map.of("folderUid", "c"), "Edit")));
        other.apply();
        transaction.apply();
        transaction.rollback();
        assertEquals(List.of(GrafanaPermission.user(10, 1)), client.acls.get(old));
        assertTrue(client.acls.get(attempted).isEmpty());
        assertEquals(List.of(GrafanaPermission.user(20, 2)), client.acls.get(unrelated));
    }

    @Test void deletesDuplicateAclRepresentationsAndKeepsInstanceStateSeparate() throws Exception {
        var first = new FakeGrafanaClient();
        var second = new FakeGrafanaClient();
        var resource = first.add(FOLDER, "f", "F", "");
        second.add(FOLDER, "f", "F", "");
        first.acls.put(resource, List.of(GrafanaPermission.user(10, 1), GrafanaPermission.user(10, 1)));
        new GrafanaBackend("monitoring", first).apply(state(access("alice", Map.of("folderUid", "f"), "View")));
        assertEquals(1, first.writes);
        assertEquals(0, second.writes);
        assertTrue(second.acls.get(resource).isEmpty());
    }

    @Test void rejectsIdentitiesAndWrongOrganizationAndCloseDoesNotMutate() throws Exception {
        var client = new FakeGrafanaClient();
        var backend = new GrafanaBackend("monitoring", client);
        var identities = new PactState(Set.of(), Set.of(new Identity("monitoring", "alice", true, null, null, null)));
        assertThrows(BackendFeatureNotImplementedException.class, () -> backend.apply(identities));
        client.badOrganization = true;
        assertThrows(BackendOperationException.class, () -> backend.apply(PactState.empty()));
        backend.close();
        assertEquals(1, client.closes);
        assertEquals(0, client.writes);
    }
}
