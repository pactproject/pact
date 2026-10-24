package io.github.pactproject.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.StateReconciler;
import io.github.pactproject.api.exception.StateReconciliationException;
import io.github.pactproject.artifactkeeper.ArtifactKeeperApiProfile;
import io.github.pactproject.artifactkeeper.ArtifactKeeperBackend;
import io.github.pactproject.artifactkeeper.ArtifactKeeperConfig;
import io.github.pactproject.artifactkeeper.client.ArtifactKeeperHttpClient;
import io.github.pactproject.core.PactCore;
import io.github.pactproject.core.exception.CoreException;
import io.github.pactproject.kubernetes.config.KubernetesConfig;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A separate JVM used to prove that persisted Kubernetes status survives a process restart. */
public final class ArtifactKeeperControllerProcess {
    public static void main(String[] args) throws Exception {
        var config = new ArtifactKeeperConfig(URI.create(System.getenv("PACT_IT_ARTIFACT_KEEPER_URL")),
                System.getenv("PACT_IT_ARTIFACT_KEEPER_TOKEN"), 100, ArtifactKeeperApiProfile.CURRENT);
        var core = new PactCore(List.of(new ArtifactKeeperBackend("packages",
                new ArtifactKeeperHttpClient(config), config.apiProfile())));
        var reconciler = new CoreReconciler(core);
        try (var provider = new KubernetesStateProvider(new KubernetesClientBuilder().build(), kubernetesConfig())) {
            provider.start(reconciler);
            if (!reconciler.reconciled.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("Initial DataAccess reconciliation did not complete");
            }
            Files.writeString(Path.of(args[0]), Integer.toString(reconciler.restoredAccesses));
            // Graceful shutdown leaves server permissions and lastAppliedSpec in place.
            System.in.read();
        }
    }

    static KubernetesConfig kubernetesConfig() {
        return new KubernetesConfig(System.getenv("PACT_IT_K8S_GROUP"),
                System.getenv().getOrDefault("PACT_IT_K8S_VERSION", "v1"),
                System.getenv().getOrDefault("PACT_IT_K8S_PLURAL", "dataaccesses"));
    }

    static final class CoreReconciler implements StateReconciler {
        private final PactCore core;
        volatile int restoredAccesses = -1;
        final java.util.concurrent.CountDownLatch reconciled = new java.util.concurrent.CountDownLatch(1);

        CoreReconciler(PactCore core) { this.core = core; }

        @Override public void restoreAppliedState(PactState state) throws StateReconciliationException {
            try {
                core.restoreAppliedState(state);
                restoredAccesses = state.accesses().size();
            } catch (CoreException e) { throw new StateReconciliationException("Cannot restore core state", e); }
        }

        @Override public void apply(PactState state) throws StateReconciliationException {
            try {
                core.apply(state);
                reconciled.countDown();
            }
            catch (CoreException e) { throw new StateReconciliationException("Cannot apply core state", e); }
        }
    }
}
