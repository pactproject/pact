package io.github.pactproject.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.pactproject.api.ManagedStateProvider;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.StateReconciler;
import io.github.pactproject.api.exception.StateProviderException;
import io.github.pactproject.kubernetes.compile.DataAccessCompiler;
import io.github.pactproject.kubernetes.config.KubernetesConfig;
import io.github.pactproject.kubernetes.controller.DataAccessController;

public final class KubernetesStateProvider
        implements ManagedStateProvider
{
    private final ManagedStateProvider controller;

    public KubernetesStateProvider(
            KubernetesClient client)
    {
        this(client, KubernetesConfig.from(java.util.Map.of()));
    }

    public KubernetesStateProvider(
            KubernetesClient client,
            KubernetesConfig config)
    {
        this.controller = DataAccessController.create(
                client,
                config,
                new DataAccessCompiler()
        );
    }

    @Override
    public PactState load()
            throws StateProviderException
    {
        return controller.load();
    }

    @Override
    public void start(StateReconciler reconciler)
            throws StateProviderException
    {
        controller.start(reconciler);
    }

    @Override
    public void close()
    {
        controller.close();
    }
}
