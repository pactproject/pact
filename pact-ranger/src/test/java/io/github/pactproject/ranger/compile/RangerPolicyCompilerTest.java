package io.github.pactproject.ranger.compile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pactproject.api.Access;
import io.github.pactproject.api.PactState;
import io.github.pactproject.api.Resource;
import io.github.pactproject.api.value.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangerPolicyCompilerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void treatsEmptyTransformDefinitionsAsUnsupported() throws Exception {
        RangerServiceDefinition definition = definition("""
                {
                  "name":"hdfs",
                  "resources":[{"name":"path"}],
                  "accessTypes":[{"name":"read"}],
                  "dataMaskDef":{},
                  "rowFilterDef":{}
                }
                """);

        assertNull(definition.dataMask());
        assertNull(definition.rowFilter());
    }

    @Test
    void allowsConfiguredBackendIdToDifferFromRangerServiceType()
            throws Exception {
        RangerServiceDefinition definition = definition("""
                {
                  "name":"hdfs",
                  "resources":[{"name":"path"}],
                  "accessTypes":[{"name":"read"}]
                }
                """);
        Access access = new Access(
                "alice",
                new Resource("ranger-backend", Map.of("path", "/data")),
                Map.of(
                        "permissions",
                        Value.object(Map.of("path", strings("read")))
                )
        );

        List<ObjectNode> policies = RangerPolicyCompiler.compile(
                new PactState(Set.of(access)),
                definition,
                "ranger-backend",
                "hdfs",
                "pact-it-hdfs"
        );

        assertEquals(1, policies.size());
        assertEquals("hdfs", policies.get(0).path("serviceType").asText());
    }

    @Test
    void compilesAccessTypesAndResourceAncestorsFromServiceDefinition()
            throws Exception {
        RangerServiceDefinition definition = definition("""
                {
                  "name":"ozone",
                  "resources":[
                    {"name":"volume"},
                    {"name":"bucket","parent":"volume"},
                    {"name":"key","parent":"bucket","recursiveSupported":true}
                  ],
                  "accessTypes":[{"name":"read"},{"name":"write"}]
                }
                """);
        Access access = new Access(
                "alice",
                new Resource(
                        "ozone",
                        Map.of(
                                "volume", "warehouse",
                                "bucket", "reports",
                                "key", "daily",
                                "isKeyRecursive", "true"
                        )
                ),
                Map.of(
                        "permissions",
                        Value.object(Map.of(
                                "volume", strings("read"),
                                "bucket", strings("write")
                        )),
                        "denyPermissions",
                        Value.object(Map.of("bucket", strings("read")))
                )
        );

        List<? extends JsonNode> policies = RangerPolicyCompiler.compile(
                new PactState(Set.of(access)),
                definition,
                "ozone",
                "ozone",
                "ozone-cluster"
        );

        assertEquals(2, policies.size());
        JsonNode volume = policyFor(policies, "volume");
        JsonNode bucket = policyFor(policies, "bucket");
        assertEquals(List.of("warehouse"),
                MAPPER.convertValue(
                        volume.path("resources").path("volume").path("values"),
                        List.class
                ));
        assertEquals("read",
                volume.path("policyItems").get(0)
                        .path("accesses").get(0).path("type").asText());
        assertEquals("warehouse",
                bucket.path("resources").path("volume")
                        .path("values").get(0).asText());
        assertEquals("reports",
                bucket.path("resources").path("bucket")
                        .path("values").get(0).asText());
        assertTrue(bucket.path("resources").path("key").isMissingNode());
        assertEquals("write",
                bucket.path("policyItems").get(0)
                        .path("accesses").get(0).path("type").asText());
        assertEquals("read",
                bucket.path("denyPolicyItems").get(0)
                        .path("accesses").get(0).path("type").asText());
        assertTrue(bucket.path("policyLabels").toString().contains("managed"));
        assertTrue(policyFor(policies, "key") == null);
    }

    @Test
    void emitsMaskPolicyAndRejectsPermissionsNotInServiceDefinition()
            throws Exception {
        RangerServiceDefinition definition = definition("""
                {
                  "name":"analytics",
                  "resources":[
                    {"name":"catalog"},
                    {"name":"schema","parent":"catalog"},
                    {"name":"table","parent":"schema"},
                    {"name":"column","parent":"table"}
                  ],
                  "accessTypes":[{"name":"select"}],
                  "dataMaskDef":{
                    "resources":[
                      {"name":"catalog"},
                      {"name":"schema"},
                      {"name":"table"},
                      {"name":"column"}
                    ],
                    "accessTypes":[{"name":"select"}],
                    "maskTypes":[
                      {"name":"CUSTOM"},
                      {"name":"MASK_HASH"}
                    ]
                  },
                  "rowFilterDef":{
                    "resources":[
                      {"name":"catalog"},
                      {"name":"schema"},
                      {"name":"table"}
                    ],
                    "accessTypes":[{"name":"select"}]
                  }
                }
                """);
        Resource target = new Resource(
                "analytics",
                Map.of(
                        "catalog", "lake",
                        "schema", "sales",
                        "table", "orders",
                        "column", "email"
                )
        );
        Access customMaskAndFilter = new Access(
                "alice",
                target,
                Map.of(
                        "dataMask",
                        Value.object(Map.of(
                                "type", Value.string("custom"),
                                "expression", Value.string("sha256(value)")
                        )),
                        "rowFilter",
                        Value.object(Map.of(
                                "expression", Value.string("tenant_id = 42")
                        ))
                )
        );
        Access hashMask = new Access(
                "bob",
                target,
                Map.of(
                        "dataMask",
                        Value.object(Map.of(
                                "type", Value.string("hash")
                        ))
                )
        );
        List<? extends JsonNode> policies = RangerPolicyCompiler.compile(
                new PactState(Set.of(customMaskAndFilter, hashMask)),
                definition,
                "analytics",
                "analytics",
                "analytics-cluster"
        );
        assertEquals(2, policies.size());
        JsonNode maskPolicy = policies.stream()
                .filter(policy -> policy.path("policyType").asInt() == 1)
                .findFirst()
                .orElseThrow();
        JsonNode rowFilterPolicy = policies.stream()
                .filter(policy -> policy.path("policyType").asInt() == 2)
                .findFirst()
                .orElseThrow();
        assertEquals(2, maskPolicy.path("dataMaskPolicyItems").size());
        assertEquals("CUSTOM",
                maskPolicy.path("dataMaskPolicyItems").get(0)
                        .path("dataMaskInfo").path("dataMaskType").asText());
        assertTrue(maskPolicy.path("dataMaskPolicyItems").toString()
                .contains("sha256(value)"));
        assertTrue(maskPolicy.path("dataMaskPolicyItems").toString()
                .contains("MASK_HASH"));
        assertEquals("tenant_id = 42",
                rowFilterPolicy.path("rowFilterPolicyItems").get(0)
                        .path("rowFilterInfo").path("filterExpr").asText());
        assertEquals("sha256(value)",
                maskPolicy.path("dataMaskPolicyItems").get(0)
                        .path("dataMaskInfo").path("valueExpr").asText());

        Access invalid = new Access(
                "alice",
                new Resource("analytics", Map.of("catalog", "lake")),
                Map.of("permissions", Value.object(Map.of(
                        "catalog", strings("admin")
                )))
        );
        assertThrows(IllegalArgumentException.class,
                () -> RangerPolicyCompiler.compile(
                        new PactState(Set.of(invalid)),
                        definition,
                        "analytics",
                        "analytics",
                        "analytics-cluster"
                ));
    }

    @Test
    void recognizesSelectGrantsAtDirectAncestorAndWildcardTargets()
            throws Exception {
        RangerServiceDefinition definition = definition("""
                {
                  "name":"analytics",
                  "resources":[
                    {"name":"catalog"},
                    {"name":"schema","parent":"catalog"},
                    {"name":"table","parent":"schema"},
                    {"name":"column","parent":"table"}
                  ],
                  "accessTypes":[{"name":"select"}]
                }
                """);
        Resource column = new Resource(
                "analytics",
                Map.of(
                        "catalog", "lake",
                        "schema", "sales",
                        "table", "orders",
                        "column", "email"
                )
        );
        Access transformation = new Access("alice", column, Map.of());
        Access directSelect = selectAccess(
                Map.of(
                        "catalog", "lake",
                        "schema", "sales",
                        "table", "orders",
                        "column", "email"
                ),
                "column"
        );
        Access tableSelect = selectAccess(
                Map.of(
                        "catalog", "lake",
                        "schema", "sales",
                        "table", "orders"
                ),
                "table"
        );
        Access wildcardSelect = selectAccess(
                Map.of(
                        "catalog", "lake",
                        "schema", "sales",
                        "table", "*"
                ),
                "table"
        );
        Access unrelatedSelect = selectAccess(
                Map.of(
                        "catalog", "lake",
                        "schema", "sales",
                        "table", "customers"
                ),
                "table"
        );

        for (Access select : List.of(
                directSelect,
                tableSelect,
                wildcardSelect
        )) {
            assertTrue(RangerPolicyCompiler.missingSelectUsers(
                    transformation,
                    new PactState(Set.of(transformation, select)),
                    definition,
                    Set.of("column")
            ).isEmpty());
        }
        assertEquals(
                Set.of("alice"),
                RangerPolicyCompiler.missingSelectUsers(
                        transformation,
                        new PactState(Set.of(transformation, unrelatedSelect)),
                        definition,
                        Set.of("column")
                )
        );
    }

    @Test
    void mergesUsersAndIgnoresOrderOfAccessCollections() throws Exception {
        RangerServiceDefinition definition = definition("""
                {
                  "name":"ozone",
                  "resources":[{"name":"volume"}],
                  "accessTypes":[{"name":"read"},{"name":"write"}]
                }
                """);
        Access alice = new Access(
                "alice",
                new Resource("ozone", Map.of("volume", "data")),
                Map.of(
                        "permissions",
                        Value.object(Map.of("volume", strings("read", "write"))),
                        "conditions",
                        strings("b == 2", "a == 1")
                )
        );
        Access bob = new Access(
                "bob",
                alice.resource(),
                Map.of(
                        "permissions",
                        Value.object(Map.of("volume", strings("write", "read"))),
                        "conditions",
                        strings("a == 1", "b == 2")
                )
        );

        List<? extends JsonNode> policies = RangerPolicyCompiler.compile(
                new PactState(Set.of(alice, bob)),
                definition,
                "ozone",
                "ozone",
                "ozone-cluster"
        );
        assertEquals(1, policies.size());
        assertEquals(2, policies.get(0).path("policyItems").size());
        assertFalse(policies.get(0).path("conditions").isEmpty());
    }

    private static RangerServiceDefinition definition(String json)
            throws Exception {
        return RangerServiceDefinition.parse(
                MAPPER.readTree(json),
                MAPPER.readTree(json).path("name").asText()
        );
    }

    private static Value strings(String... values) {
        return Value.set(
                java.util.Arrays.stream(values)
                        .map(Value::string)
                        .collect(java.util.stream.Collectors.toSet())
        );
    }

    private static Access selectAccess(
            Map<String, String> target,
            String level
    ) {
        return new Access(
                "alice",
                new Resource("analytics", target),
                Map.of("permissions", Value.object(Map.of(
                        level,
                        strings("select")
                )))
        );
    }

    private static JsonNode policyFor(
            List<? extends JsonNode> policies,
            String resource
    ) {
        return policies.stream()
                .filter(policy -> policy.path("resources").has(resource))
                .filter(policy -> policy.path("resources").size()
                        == (resource.equals("volume") ? 1 : 2))
                .findFirst()
                .orElse(null);
    }
}
