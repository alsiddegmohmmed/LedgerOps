package com.ledgerops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OpenApiContractTests {

    private static final Path CONTRACT = Path.of(
            "docs/api/ledgerops-openapi-v0.1.yaml"
    );

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void contractIsValidYamlWithTheExactRelease01Vocabulary() throws IOException {
        Map<String, Object> contract = loadContract();

        assertEquals("3.1.0", contract.get("openapi"));
        assertEquals(
                Set.of(
                        "/api/v1/tenants",
                        "/api/v1/tenants/{tenantId}",
                        "/api/v1/tenants/{tenantId}/activate",
                        "/api/v1/tenants/{tenantId}/suspend",
                        "/api/v1/tenants/{tenantId}/archive",
                        "/api/v1/payments"
                ),
                paths(contract).keySet()
        );

        Map<String, Object> payment = schema(contract, "Payment");
        Map<String, Object> paymentProperties = map(payment.get("properties"));
        Map<String, Object> status = map(paymentProperties.get("status"));
        assertEquals(
                List.of(
                        "CREATED",
                        "VALIDATING",
                        "RISK_REVIEW",
                        "APPROVED",
                        "REJECTED",
                        "PROCESSING",
                        "COMPLETED",
                        "FAILED",
                        "REVERSED"
                ),
                status.get("enum")
        );

        Map<String, Object> problem = schema(contract, "Problem");
        assertTrue(list(problem.get("required")).containsAll(List.of(
                "type",
                "title",
                "status",
                "detail",
                "code",
                "correlationId",
                "effect",
                "retryable",
                "nextAction"
        )));
        String raw = Files.readString(CONTRACT);
        assertTrue(raw.contains("X-Correlation-Id"));
        assertTrue(raw.contains("idempotencyKey"));
        assertTrue(map(contract.get("info")).get("description").toString()
                .contains("Release 0.1 intentionally runs without authentication"));
        assertFalse(raw.contains("X-Trace-Id"));
        assertFalse(raw.contains("traceId:"));
    }

    @Test
    void everyRelease01HttpOperationIsDocumentedAndEveryDocumentedOperationExists()
            throws IOException {
        Set<String> runtimeOperations = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            String packageName = method.getBeanType().getPackageName();
            if (!packageName.equals("com.ledgerops.tenancy.api")
                    && !packageName.equals("com.ledgerops.payment.api")) {
                return;
            }
            for (String path : mapping.getPatternValues()) {
                for (RequestMethod requestMethod : mapping.getMethodsCondition().getMethods()) {
                    runtimeOperations.add(requestMethod.name() + " " + path);
                }
            }
        });

        Set<String> documentedOperations = new LinkedHashSet<>();
        paths(loadContract()).forEach((path, pathDefinition) ->
                map(pathDefinition).forEach((key, value) -> {
                    String method = key.toUpperCase();
                    if (Set.of("GET", "POST", "PUT", "PATCH", "DELETE")
                            .contains(method)) {
                        documentedOperations.add(method + " " + path);
                        assertNotNull(map(value).get("operationId"));
                    }
                })
        );

        assertEquals(runtimeOperations, documentedOperations);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadContract() throws IOException {
        try (InputStream input = Files.newInputStream(CONTRACT)) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            return (Map<String, Object>) loaded;
        }
    }

    private Map<String, Object> paths(Map<String, Object> contract) {
        return map(contract.get("paths"));
    }

    private Map<String, Object> schema(
            Map<String, Object> contract,
            String name
    ) {
        Map<String, Object> components = map(contract.get("components"));
        return map(map(components.get("schemas")).get(name));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Object value) {
        return (List<String>) value;
    }
}
