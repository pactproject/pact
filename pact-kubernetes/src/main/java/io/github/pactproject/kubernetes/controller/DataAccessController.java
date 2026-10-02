package io.github.pactproject.kubernetes.controller;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.ManagedStateProvider;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.StateReconciler;
import io.github.pactproject.api.exception.StateProviderException;
import io.github.pactproject.api.exception.StateReconciliationException;
import io.github.pactproject.kubernetes.compile.DataAccessCompiler;
import io.github.pactproject.kubernetes.config.KubernetesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class DataAccessController
        implements ManagedStateProvider
{
    private static final Logger log =
            LoggerFactory.getLogger(DataAccessController.class);

    private static final String FINALIZER_NAME = "data-access";
    private final KubernetesClient client;
    private final KubernetesConfig config;
    private final ResourceDefinitionContext resourceContext;
    private final DataAccessCompiler compiler;
    private final String finalizer;
    private final ExecutorService eventQueue =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "pact-dataaccess-events");
                thread.setDaemon(false);
                return thread;
            });
    private final Map<ResourceKey, AppliedResource> appliedResources =
            new HashMap<>();

    private StateReconciler reconciler;
    private SharedIndexInformer<GenericKubernetesResource> informer;
    private boolean started;
    private boolean acceptingEvents = true;

    private DataAccessController(
            KubernetesClient client,
            KubernetesConfig config,
            DataAccessCompiler compiler)
    {
        this.client = client;
        this.config = config;
        this.resourceContext = new ResourceDefinitionContext.Builder()
                .withGroup(config.group())
                .withVersion(config.version())
                .withPlural(config.plural())
                .withKind("DataAccess")
                .withNamespaced(true)
                .build();
        this.compiler = compiler;
        this.finalizer = config.group() + "/" + FINALIZER_NAME;
    }

    public static DataAccessController create(
            KubernetesClient client,
            KubernetesConfig config,
            DataAccessCompiler compiler)
    {
        return new DataAccessController(client, config, compiler);
    }

    @Override
    public PactState load()
            throws StateProviderException
    {
        try {
            return compileResources(
                    client.genericKubernetesResources(resourceContext)
                            .inAnyNamespace()
                            .list()
                            .getItems()
            );
        }
        catch (RuntimeException e) {
            throw new StateProviderException(
                    "Failed to load DataAccess resources from Kubernetes",
                    e
            );
        }
    }

    @Override
    public synchronized void start(StateReconciler reconciler)
            throws StateProviderException
    {
        if (started) {
            throw new StateProviderException(
                    "DataAccess controller has already been started"
            );
        }
        this.reconciler = reconciler;

        try {
            List<GenericKubernetesResource> resources =
                    client.genericKubernetesResources(resourceContext)
                            .inAnyNamespace()
                            .list()
                            .getItems();
            restoreAppliedResources(resources);
            reconciler.restoreAppliedState(aggregateAppliedState());

            informer = client.genericKubernetesResources(resourceContext)
                    .inAnyNamespace()
                    .inform(
                            new DataAccessEventHandler(),
                            config.informerResyncMillis()
                    );
            awaitInformerSync();
            started = true;
            log.info(
                    "Started DataAccess informer for {}/{}",
                    config.group(),
                    config.plural()
            );
        }
        catch (Exception e) {
            if (informer != null) {
                informer.stop();
            }
            eventQueue.shutdownNow();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StateProviderException(
                    "Failed to start DataAccess controller",
                    e
            );
        }
    }

    private void restoreAppliedResources(
            List<GenericKubernetesResource> resources)
    {
        Map<ResourceKey, AppliedResource> restored = new HashMap<>();
        for (GenericKubernetesResource resource : resources) {
            ResourceKey key = resourceKey(resource);
            Map<String, ?> status = optionalObject(
                    resource.getAdditionalProperties().get("status"),
                    "status"
            );
            Object lastAppliedSpec = status.get("lastAppliedSpec");
            if (lastAppliedSpec == null) {
                continue;
            }
            Map<String, ?> spec = requiredObject(
                    lastAppliedSpec,
                    "status.lastAppliedSpec"
            );
            restored.put(
                    key,
                    new AppliedResource(
                            spec,
                            compiler.compile(List.of(spec))
                    )
            );
        }
        appliedResources.clear();
        appliedResources.putAll(restored);
    }

    private PactState compileResources(
            List<GenericKubernetesResource> resources)
    {
        List<Map<String, ?>> specs = new ArrayList<>(resources.size());
        for (GenericKubernetesResource resource : resources) {
            specs.add(
                    requiredObject(
                            resource.getAdditionalProperties().get("spec"),
                            "spec"
                    )
            );
        }
        return compiler.compile(specs);
    }

    private void awaitInformerSync()
            throws InterruptedException
    {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(
                        config.informerStartTimeoutSeconds()
                );
        while (!informer.hasSynced()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "Timed out waiting for DataAccess informer cache sync"
                );
            }
            Thread.sleep(10L);
        }
    }

    @Override
    public synchronized void close()
    {
        if (!acceptingEvents) {
            return;
        }
        acceptingEvents = false;
        eventQueue.shutdown();
        awaitQueueTermination();
        if (informer != null) {
            informer.stop();
        }
        client.close();
        log.info("Stopped DataAccess controller");
    }

    private void awaitQueueTermination()
    {
        boolean interrupted = false;
        while (!eventQueue.isTerminated()) {
            try {
                eventQueue.awaitTermination(1L, TimeUnit.DAYS);
            }
            catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleAdd(GenericKubernetesResource resource)
    {
        try {
            ResourceKey key = resourceKey(resource);
            generation(resource);
            observedGeneration(resource);
            if (isDeleting(resource)) {
                enqueue(new DataAccessEvent(EventType.DELETE, resource, key));
                return;
            }
            FinalizerResult finalizerResult = ensureFinalizer(key);
            if (finalizerResult == FinalizerResult.DELETING) {
                enqueue(new DataAccessEvent(EventType.DELETE, resource, key));
                return;
            }
            enqueue(new DataAccessEvent(EventType.APPLY, resource, key));
        }
        catch (RuntimeException e) {
            log.error("Ignoring invalid DataAccess add event: {}", e.getMessage(), e);
        }
    }

    private void handleUpdate(
            GenericKubernetesResource oldResource,
            GenericKubernetesResource resource)
    {
        try {
            ResourceKey key = resourceKey(resource);
            if (isDeleting(resource)) {
                if (isStatusOnlyDeletionUpdate(oldResource, resource)) {
                    return;
                }
                enqueue(new DataAccessEvent(EventType.DELETE, resource, key));
                return;
            }

            long generation = generation(resource);
            Long observedGeneration = observedGeneration(resource);
            FinalizerResult finalizerResult = ensureFinalizer(key);
            if (finalizerResult == FinalizerResult.DELETING) {
                enqueue(new DataAccessEvent(EventType.DELETE, resource, key));
                return;
            }
            if (finalizerResult == FinalizerResult.ADDED) {
                enqueue(new DataAccessEvent(EventType.APPLY, resource, key));
                return;
            }
            if (generation == generation(oldResource)) {
                return;
            }
            if (observedGeneration != null
                    && generation == observedGeneration) {
                return;
            }
            enqueue(new DataAccessEvent(EventType.APPLY, resource, key));
        }
        catch (RuntimeException e) {
            log.error("Ignoring invalid DataAccess update event: {}", e.getMessage(), e);
        }
    }

    private void handleDelete(GenericKubernetesResource resource)
    {
        try {
            enqueue(
                    new DataAccessEvent(
                            EventType.DELETE,
                            resource,
                            resourceKey(resource)
                    )
            );
        }
        catch (RuntimeException e) {
            log.error("Ignoring invalid DataAccess delete event: {}", e.getMessage(), e);
        }
    }

    private synchronized void enqueue(DataAccessEvent event)
    {
        if (!acceptingEvents) {
            return;
        }
        eventQueue.execute(() -> process(event));
    }

    private void process(DataAccessEvent event)
    {
        log.info(
                "Processing DataAccess {} event for {}/{}",
                event.type(),
                event.key().namespace(),
                event.key().name()
        );
        try {
            if (event.type() == EventType.DELETE) {
                reconcileDelete(event);
            }
            else {
                reconcileApply(event);
            }
        }
        catch (Exception e) {
            log.error(
                    "Failed to process DataAccess event for {}/{}: {}",
                    event.key().namespace(),
                    event.key().name(),
                    e.getMessage(),
                    e
            );
        }
    }

    private void reconcileApply(DataAccessEvent event)
    {
        long generation = generation(event.resource());
        Map<String, ?> spec;
        PactState resourceState;
        try {
            spec = requiredObject(
                    event.resource().getAdditionalProperties().get("spec"),
                    "spec"
            );
            resourceState = compiler.compile(List.of(spec));
        }
        catch (RuntimeException e) {
            log.warn(
                    "Invalid DataAccess {}/{}: {}",
                    event.key().namespace(),
                    event.key().name(),
                    e.getMessage()
            );
            patchStatus(
                    event.key(),
                    status("Error", e.getMessage(), generation, null, false)
            );
            return;
        }

        patchStatus(
                event.key(),
                status("Applying", null, generation, null, false)
        );

        Map<ResourceKey, AppliedResource> next =
                new HashMap<>(appliedResources);
        next.put(
                event.key(),
                new AppliedResource(spec, resourceState)
        );
        try {
            reconciler.apply(aggregate(next));
            appliedResources.clear();
            appliedResources.putAll(next);
            patchStatus(
                    event.key(),
                    status("Ready", null, generation, spec, true)
            );
        }
        catch (StateReconciliationException e) {
            log.error(
                    "Backend reconciliation failed for DataAccess {}/{}",
                    event.key().namespace(),
                    event.key().name(),
                    e
            );
            patchStatus(
                    event.key(),
                    status("Error", e.getMessage(), generation, null, false)
            );
        }
    }

    private void reconcileDelete(DataAccessEvent event)
    {
        long generation = generation(event.resource());
        patchStatus(
                event.key(),
                status("Applying", null, generation, null, false)
        );
        Map<ResourceKey, AppliedResource> next =
                new HashMap<>(appliedResources);
        next.remove(event.key());

        try {
            reconciler.apply(aggregate(next));
            appliedResources.clear();
            appliedResources.putAll(next);
        }
        catch (StateReconciliationException e) {
            log.error(
                    "Cleanup failed for DataAccess {}/{}",
                    event.key().namespace(),
                    event.key().name(),
                    e
            );
            patchStatus(
                    event.key(),
                    status("Error", e.getMessage(), generation, null, false)
            );
            return;
        }

        try {
            removeFinalizer(event.key());
        }
        catch (RuntimeException e) {
            log.error(
                    "Failed to remove DataAccess finalizer for {}/{}",
                    event.key().namespace(),
                    event.key().name(),
                    e
            );
            patchStatus(
                    event.key(),
                    status("Error", e.getMessage(), generation, null, false)
            );
        }
    }

    private PactState aggregateAppliedState()
    {
        return aggregate(appliedResources);
    }

    private PactState aggregate(
            Map<ResourceKey, AppliedResource> resources)
    {
        Set<Access> accesses = new HashSet<>();
        resources.values().forEach(
                applied -> accesses.addAll(applied.state().accesses())
        );
        return new PactState(accesses);
    }

    private Map<String, Object> status(
            String phase,
            String error,
            long generation,
            Map<String, ?> spec,
            boolean updateLastAppliedSpec)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", phase);
        result.put("lastError", error);
        result.put("observedGeneration", generation);
        if (updateLastAppliedSpec) {
            result.put("lastAppliedSpec", spec);
        }
        return result;
    }

    private void patchStatus(
            ResourceKey key,
            Map<String, Object> desiredStatus)
    {
        List<Long> retryDelays = config.statusRetryDelaysMillis();
        for (int attempt = 0; attempt <= retryDelays.size(); attempt++) {
            try {
                GenericKubernetesResource current = resource(key).get();
                if (current == null) {
                    return;
                }
                Map<String, ?> currentStatus = optionalObject(
                        current.getAdditionalProperties().get("status"),
                        "status"
                );
                if (desiredStatus.entrySet().stream().allMatch(
                        entry -> java.util.Objects.equals(
                                currentStatus.get(entry.getKey()),
                                entry.getValue()
                        )
                )) {
                    return;
                }

                GenericKubernetesResource patch = patchResource(
                        key,
                        current.getMetadata().getResourceVersion()
                );
                patch.setAdditionalProperty(
                        "status",
                        desiredStatus
                );
                resource(key).patchStatus(patch);
                return;
            }
            catch (RuntimeException e) {
                if (attempt == retryDelays.size()) {
                    log.error(
                            "Failed to patch DataAccess status for {}/{} after retries",
                            key.namespace(),
                            key.name(),
                            e
                    );
                    return;
                }
                try {
                    Thread.sleep(retryDelays.get(attempt));
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    log.error(
                            "Interrupted while retrying DataAccess status patch for {}/{}",
                            key.namespace(),
                            key.name(),
                            interrupted
                    );
                    return;
                }
            }
        }
    }

    private FinalizerResult ensureFinalizer(ResourceKey key)
    {
        GenericKubernetesResource current = resource(key).get();
        if (current == null || isDeleting(current)) {
            return FinalizerResult.DELETING;
        }
        List<String> finalizers = finalizers(current);
        if (finalizers.contains(finalizer)) {
            return FinalizerResult.PRESENT;
        }
        finalizers.add(finalizer);

        GenericKubernetesResource patch =
                patchResource(key, current.getMetadata().getResourceVersion());
        patch.getMetadata().setFinalizers(finalizers);
        client.genericKubernetesResources(resourceContext)
                .inNamespace(key.namespace())
                .withName(key.name())
                .patch(
                        PatchContext.of(PatchType.JSON_MERGE),
                        patch
                );
        return FinalizerResult.ADDED;
    }

    private void removeFinalizer(ResourceKey key)
    {
        GenericKubernetesResource current = resource(key).get();
        if (current == null) {
            return;
        }
        List<String> finalizers = finalizers(current);
        if (!finalizers.remove(finalizer)) {
            return;
        }
        current.getMetadata().setFinalizers(finalizers);
        client.genericKubernetesResources(resourceContext)
                .inNamespace(key.namespace())
                .withName(key.name())
                .patch(current);
    }

    private io.fabric8.kubernetes.client.dsl.Resource<GenericKubernetesResource> resource(
            ResourceKey key)
    {
        return client.genericKubernetesResources(resourceContext)
                .inNamespace(key.namespace())
                .withName(key.name());
    }

    private GenericKubernetesResource patchResource(
            ResourceKey key,
            String resourceVersion)
    {
        GenericKubernetesResource patch = new GenericKubernetesResource();
        patch.setApiVersion(
                resourceContext.getGroup()
                        + "/"
                        + resourceContext.getVersion()
        );
        patch.setKind(resourceContext.getKind());
        patch.setMetadata(
                new ObjectMetaBuilder()
                        .withName(key.name())
                        .withNamespace(key.namespace())
                        .withResourceVersion(resourceVersion)
                        .build()
        );
        return patch;
    }

    private ResourceKey resourceKey(GenericKubernetesResource resource)
    {
        if (resource.getMetadata() == null
                || isBlank(resource.getMetadata().getName())
                || isBlank(resource.getMetadata().getNamespace())) {
            throw new IllegalArgumentException(
                    "DataAccess metadata.name and metadata.namespace are required"
            );
        }
        return new ResourceKey(
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName()
        );
    }

    private long generation(GenericKubernetesResource resource)
    {
        Long generation = resource.getMetadata() == null
                ? null
                : resource.getMetadata().getGeneration();
        if (generation == null || generation < 1L) {
            throw new IllegalArgumentException(
                    "DataAccess metadata.generation must be a positive integer"
            );
        }
        return generation;
    }

    private Long observedGeneration(GenericKubernetesResource resource)
    {
        Map<String, ?> status = optionalObject(
                resource.getAdditionalProperties().get("status"),
                "status"
        );
        Object value = status.get("observedGeneration");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "DataAccess status.observedGeneration must be a number"
            );
        }
        return number.longValue();
    }

    private boolean isDeleting(GenericKubernetesResource resource)
    {
        return resource.getMetadata() != null
                && resource.getMetadata().getDeletionTimestamp() != null;
    }

    private boolean isStatusOnlyDeletionUpdate(
            GenericKubernetesResource oldResource,
            GenericKubernetesResource newResource)
    {
        // Status writes during cleanup must not enqueue another cleanup pass.
        if (!isDeleting(oldResource)
                || java.util.Objects.equals(
                        oldResource.getAdditionalProperties().get("status"),
                        newResource.getAdditionalProperties().get("status")
                )
                || !java.util.Objects.equals(
                        oldResource.getMetadata().getDeletionTimestamp(),
                        newResource.getMetadata().getDeletionTimestamp()
                )
                || !java.util.Objects.equals(
                        oldResource.getMetadata().getGeneration(),
                        newResource.getMetadata().getGeneration()
                )
                || !finalizers(oldResource).equals(finalizers(newResource))) {
            return false;
        }
        return java.util.Objects.equals(
                        oldResource.getAdditionalProperties().get("spec"),
                        newResource.getAdditionalProperties().get("spec")
                )
                && java.util.Objects.equals(
                        oldResource.getMetadata().getLabels(),
                        newResource.getMetadata().getLabels()
                )
                && java.util.Objects.equals(
                        oldResource.getMetadata().getAnnotations(),
                        newResource.getMetadata().getAnnotations()
                );
    }

    private List<String> finalizers(GenericKubernetesResource resource)
    {
        List<String> result = new ArrayList<>();
        if (resource.getMetadata() != null
                && resource.getMetadata().getFinalizers() != null) {
            result.addAll(resource.getMetadata().getFinalizers());
        }
        return result;
    }

    private Map<String, ?> requiredObject(Object value, String field)
    {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "DataAccess " + field + " must be an object"
            );
        }
        return stringKeyedMap(map, field);
    }

    private Map<String, ?> optionalObject(Object value, String field)
    {
        if (value == null) {
            return Map.of();
        }
        return requiredObject(value, field);
    }

    private Map<String, ?> stringKeyedMap(
            Map<?, ?> map,
            String field)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "DataAccess " + field + " keys must be strings"
                );
            }
            result.put(key, entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }

    private final class DataAccessEventHandler
            implements ResourceEventHandler<GenericKubernetesResource>
    {
        @Override
        public void onAdd(GenericKubernetesResource resource)
        {
            handleAdd(resource);
        }

        @Override
        public void onUpdate(
                GenericKubernetesResource oldResource,
                GenericKubernetesResource newResource)
        {
            handleUpdate(oldResource, newResource);
        }

        @Override
        public void onDelete(
                GenericKubernetesResource resource,
                boolean deletedFinalStateUnknown)
        {
            handleDelete(resource);
        }
    }

    private enum EventType
    {
        APPLY,
        DELETE
    }

    private enum FinalizerResult
    {
        PRESENT,
        ADDED,
        DELETING
    }

    private record DataAccessEvent(
            EventType type,
            GenericKubernetesResource resource,
            ResourceKey key)
    {
    }

    private record ResourceKey(String namespace, String name)
    {
    }

    private record AppliedResource(
            Map<String, ?> spec,
            PactState state)
    {
    }
}
