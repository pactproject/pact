package io.github.pactproject.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.pactproject.api.StateProvider;
import io.github.pactproject.api.StateProviderFactory;
import io.github.pactproject.kubernetes.config.KubernetesConfig;

import java.util.Map;

public final class KubernetesStateProviderFactory
        implements StateProviderFactory
{
    @Override
    public String type()
    {
        return "kubernetes";
    }

    @Override
    public StateProvider create(
            Map<String, String> config)
    {
        KubernetesConfig kubernetesConfig =
                KubernetesConfig.from(config);
        KubernetesClient client =
                new KubernetesClientBuilder().build();

        try {
            return new KubernetesStateProvider(
                    client,
                    kubernetesConfig
            );
        }
        catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }
}
