package io.github.pactproject.kubernetes.controller;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.Identity;
import io.github.pactproject.api.ManagedStateProvider;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.SecretValue;
import io.github.pactproject.api.StateReconciler;
import io.github.pactproject.api.exception.StateProviderException;
import io.github.pactproject.api.exception.StateReconciliationException;
import io.github.pactproject.kubernetes.compile.DataAccessCompiler;
import io.github.pactproject.kubernetes.config.KubernetesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
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
            long syncStartNanos = System.nanoTime();
            awaitInformerSync();
            started = true;
            log.info(
                    "Started DataAccess informer for {}/{}; restored applied state "
                            + "for {}/{} resource(s), informer sync took {} ms",
                    config.group(),
                    config.plural(),
                    appliedResources.size(),
                    resources.size(),
                    TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - syncStartNanos
                    )
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
                            restoreIdentityVersions(
                                    compiler.compile(List.of(spec)),
                                    status.get("lastAppliedIdentityVersions"),
                                    key.namespace()
                            )
                    )
            );
        }
        appliedResources.clear();
        appliedResources.putAll(restored);
    }

    private PactState compileResources(
            List<GenericKubernetesResource> resources)
    {
        Map<ResourceKey, AppliedResource> compiledResources =
                new HashMap<>();
        for (GenericKubernetesResource resource : resources) {
            Map<String, ?> spec = requiredObject(
                    resource.getAdditionalProperties().get("spec"),
                    "spec"
            );
            ResourceKey key = resourceKey(resource);
            PactState compiled = resolveCredentials(
                    compiler.compile(List.of(spec)),
                    key.namespace()
            );
            compiledResources.put(key, new AppliedResource(spec, compiled));
        }
        return aggregate(compiledResources);
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
                if (isInformerResync(oldResource, resource)
                        && hasPasswordSecretVersionChanged(resource, key)) {
                    enqueue(new DataAccessEvent(EventType.APPLY, resource, key));
                }
                else {
                    log.debug(
                            "Ignoring DataAccess update for {}/{} without a spec change",
                            key.namespace(),
                            key.name()
                    );
                }
                return;
            }
            if (observedGeneration != null
                    && generation == observedGeneration) {
                log.debug(
                        "Ignoring DataAccess update for {}/{} at observed generation {}",
                        key.namespace(),
                        key.name(),
                        generation
                );
                return;
            }
            enqueue(new DataAccessEvent(EventType.APPLY, resource, key));
        }
        catch (RuntimeException e) {
            log.error("Ignoring invalid DataAccess update event: {}", e.getMessage(), e);
        }
    }

    private boolean isInformerResync(
            GenericKubernetesResource oldResource,
            GenericKubernetesResource newResource)
    {
        String oldVersion = oldResource.getMetadata() == null
                ? null
                : oldResource.getMetadata().getResourceVersion();
        return oldVersion != null
                && java.util.Objects.equals(
                        oldVersion,
                        newResource.getMetadata().getResourceVersion()
                );
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

    private boolean hasPasswordSecretVersionChanged(
            GenericKubernetesResource resource,
            ResourceKey key)
    {
        Map<String, ?> spec = requiredObject(
                resource.getAdditionalProperties().get("spec"),
                "spec"
        );
        PactState declared;
        try {
            declared = compiler.compile(List.of(spec));
        }
        catch (IllegalArgumentException invalidSpec) {
            return false;
        }
        if (declared.identities().stream().noneMatch(
                identity -> identity.passwordSource() != null
        )) {
            return false;
        }

        Map<String, ?> currentStatus = optionalObject(
                resource.getAdditionalProperties().get("status"),
                "status"
        );
        PactState lastApplied = restoreIdentityVersions(
                declared,
                currentStatus.get("lastAppliedIdentityVersions"),
                key.namespace()
        );
        try {
            PactState current = resolveCredentials(declared, key.namespace());
            return !lastApplied.identities().equals(current.identities());
        }
        catch (IllegalArgumentException unresolvedSecret) {
            return true;
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
        log.debug(
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
            resourceState = resolveCredentials(
                    compiler.compile(List.of(spec)),
                    event.key().namespace()
            );
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

        Map<ResourceKey, AppliedResource> next =
                new HashMap<>(appliedResources);
        next.put(
                event.key(),
                new AppliedResource(spec, resourceState)
        );
        try {
            PactState desired = aggregate(next);
            patchStatus(
                    event.key(),
                    status("Applying", null, generation, null, false)
            );
            reconciler.apply(desired);
            appliedResources.clear();
            appliedResources.putAll(withoutSecrets(next));
            patchStatus(
                    event.key(),
                    status(
                            "Ready",
                            null,
                            generation,
                            spec,
                            true,
                            secretVersions(resourceState)
                    )
            );
            log.info(
                    "Reconciled DataAccess {}/{} generation {} with {} access(es) "
                            + "and {} identity/identities",
                    event.key().namespace(),
                    event.key().name(),
                    generation,
                    resourceState.accesses().size(),
                    resourceState.identities().size()
            );
        }
        catch (IllegalArgumentException e) {
            patchStatus(
                    event.key(),
                    status(
                            "Error",
                            failureDetails(e),
                            generation,
                            null,
                            false
                    )
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
                    status(
                            "Error",
                            failureDetails(e),
                            generation,
                            null,
                            false
                    )
            );
        }
    }

    private String failureDetails(Throwable failure)
    {
        StringJoiner details = new StringJoiner(" | ");
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            String message = current.getMessage();
            details.add(current.getClass().getSimpleName()
                    + (message == null || message.isBlank()
                    ? ""
                    : ": " + message));
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                pending.addLast(suppressed);
            }
        }
        return details.toString();
    }

    private void reconcileDelete(DataAccessEvent event)
    {
        if (!appliedResources.containsKey(event.key())) {
            log.debug(
                    "Skipping DataAccess delete for {}/{} because its "
                            + "applied state is already absent",
                    event.key().namespace(),
                    event.key().name()
            );
            removeFinalizer(event.key());
            return;
        }

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
                    status(
                            "Error",
                            failureDetails(e),
                            generation,
                            null,
                            false
                    )
            );
            return;
        }

        try {
            removeFinalizer(event.key());
            log.info(
                    "Deleted DataAccess {}/{} after backend cleanup",
                    event.key().namespace(),
                    event.key().name()
            );
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
                    status(
                            "Error",
                            failureDetails(e),
                            generation,
                            null,
                            false
                    )
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
        Set<Identity> identities = new HashSet<>();
        Map<String, ResourceKey> identityOwners = new HashMap<>();
        resources.forEach((key, applied) -> {
            accesses.addAll(applied.state().accesses());
            for (Identity identity : applied.state().identities()) {
                String identityKey = identity.backendId()
                        + "\u0000" + identity.principal();
                ResourceKey previousOwner = identityOwners.putIfAbsent(
                        identityKey,
                        key
                );
                if (previousOwner != null && !previousOwner.equals(key)) {
                    throw new IllegalArgumentException(
                            "Identity '" + identity.principal()
                                    + "' for backend '" + identity.backendId()
                                    + "' must be declared by only one DataAccess"
                    );
                }
                identities.add(identity);
            }
        });
        return new PactState(accesses, identities);
    }

    private PactState resolveCredentials(PactState state, String namespace)
    {
        Set<Identity> identities = new HashSet<>();
        for (Identity identity : state.identities()) {
            if (identity.passwordSource() == null) {
                identities.add(identity);
                continue;
            }
            int separator = identity.passwordSource().indexOf('/');
            if (separator < 1 || separator == identity.passwordSource().length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid password Secret reference for identity '"
                                + identity.principal() + "'"
                );
            }
            String secretName =
                    identity.passwordSource().substring(0, separator);
            String secretKey =
                    identity.passwordSource().substring(separator + 1);
            Secret secret = client.secrets()
                    .inNamespace(namespace)
                    .withName(secretName)
                    .get();
            if (secret == null) {
                throw new IllegalArgumentException(
                        "Password Secret '" + secretName
                                + "' does not exist in namespace '"
                                + namespace + "'"
                );
            }
            String encoded = secret.getData() == null
                    ? null
                    : secret.getData().get(secretKey);
            if (encoded == null) {
                throw new IllegalArgumentException(
                        "Password Secret '" + secretName
                                + "' has no key '" + secretKey + "'"
                );
            }
            String version = secret.getMetadata() == null
                    ? null
                    : secret.getMetadata().getResourceVersion();
            if (isBlank(version)) {
                throw new IllegalArgumentException(
                        "Password Secret '" + secretName
                                + "' has no resourceVersion"
                );
            }
            identities.add(identity.withPassword(
                    qualifiedSecretSource(namespace, identity.passwordSource()),
                    version,
                    new SecretValue(decodeSecret(encoded, secretName, secretKey))
            ));
        }
        return new PactState(state.accesses(), identities);
    }

    private String decodeSecret(
            String encoded,
            String secretName,
            String secretKey)
    {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Password Secret '" + secretName
                            + "' key '" + secretKey + "' is not valid base64"
            );
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "Password Secret '" + secretName
                                + "' key '" + secretKey + "' must not be empty"
                );
            }
            return value;
        }
        catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    "Password Secret '" + secretName
                            + "' key '" + secretKey
                            + "' must contain UTF-8 text"
            );
        }
        finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private PactState restoreIdentityVersions(
            PactState state,
            Object versionValue,
            String namespace)
    {
        if (versionValue == null) {
            return state;
        }
        if (!(versionValue instanceof Iterable<?> entries)) {
            throw new IllegalArgumentException(
                    "DataAccess status.lastAppliedIdentityVersions must be an array"
            );
        }
        Map<IdentityVersionKey, String> versions = new HashMap<>();
        for (Object entryValue : entries) {
            if (!(entryValue instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException(
                        "DataAccess status identity version entry must be an object"
                );
            }
            String backend = statusString(entry, "backend");
            String principal = statusString(entry, "principal");
            String source = statusString(entry, "source");
            String version = statusString(entry, "resourceVersion");
            versions.put(
                    new IdentityVersionKey(backend, principal, source),
                    version
            );
        }
        Set<Identity> identities = new HashSet<>();
        for (Identity identity : state.identities()) {
            String version = versions.get(new IdentityVersionKey(
                    identity.backendId(),
                    identity.principal(),
                    qualifiedSecretSource(
                            namespace,
                            identity.passwordSource()
                    )
            ));
            identities.add(
                    identity.passwordSource() != null && version != null
                            ? identity.withPasswordVersion(
                                    qualifiedSecretSource(
                                            namespace,
                                            identity.passwordSource()
                                    ),
                                    version
                            )
                            : identity
            );
        }
        return new PactState(state.accesses(), identities);
    }

    private String qualifiedSecretSource(String namespace, String source)
    {
        return source == null ? null : namespace + "/" + source;
    }

    private String statusString(Map<?, ?> values, String field)
    {
        Object value = values.get(field);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(
                    "DataAccess status identity version field '"
                            + field + "' must be a non-empty string"
            );
        }
        return string;
    }

    private List<Map<String, String>> secretVersions(PactState state)
    {
        List<Identity> identities = state.identities().stream()
                .filter(identity ->
                        identity.passwordSource() != null
                                && identity.passwordVersion() != null)
                .sorted(java.util.Comparator
                        .comparing(Identity::backendId)
                        .thenComparing(Identity::principal))
                .toList();
        List<Map<String, String>> result = new ArrayList<>();
        for (Identity identity : identities) {
            result.add(Map.of(
                    "backend", identity.backendId(),
                    "principal", identity.principal(),
                    "source", identity.passwordSource(),
                    "resourceVersion", identity.passwordVersion()
            ));
        }
        return List.copyOf(result);
    }

    private Map<ResourceKey, AppliedResource> withoutSecrets(
            Map<ResourceKey, AppliedResource> resources)
    {
        Map<ResourceKey, AppliedResource> result = new HashMap<>();
        resources.forEach((key, resource) ->
                result.put(
                        key,
                        new AppliedResource(
                                resource.spec(),
                                resource.state().withoutSecrets()
                        )
                )
        );
        return result;
    }

    private Map<String, Object> status(
            String phase,
            String error,
            long generation,
            Map<String, ?> spec,
            boolean updateLastAppliedSpec)
    {
        return status(
                phase,
                error,
                generation,
                spec,
                updateLastAppliedSpec,
                null
        );
    }

    private Map<String, Object> status(
            String phase,
            String error,
            long generation,
            Map<String, ?> spec,
            boolean updateLastAppliedSpec,
            List<Map<String, String>> identityVersions)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", phase);
        result.put("lastError", error);
        result.put("observedGeneration", generation);
        if (updateLastAppliedSpec) {
            result.put("lastAppliedSpec", spec);
            result.put(
                    "lastAppliedIdentityVersions",
                    identityVersions == null ? List.of() : identityVersions
            );
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
        log.debug(
                "Added DataAccess finalizer to {}/{}",
                key.namespace(),
                key.name()
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
        log.debug(
                "Removed DataAccess finalizer from {}/{}",
                key.namespace(),
                key.name()
        );
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

    private record IdentityVersionKey(
            String backend,
            String principal,
            String source)
    {
    }
}
