package com.ledgerops.simulator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@RestController
final class ProviderSimulatorController {
    private static final String SUBMIT_PATH = "/provider/v1/payments";
    private static final String STATUS_PATH = "/provider/v1/payment-status-queries";
    private final JdbcTemplate jdbc;
    private final HmacVerifier hmac;
    private final Clock clock;
    private final String providerClientId;
    private final JsonMapper json = JsonMapper.builder().build();

    ProviderSimulatorController(JdbcTemplate jdbc, HmacVerifier hmac, Clock clock,
            @Value("${ledgerops.simulator.provider-client-id}") String providerClientId) {
        this.jdbc = jdbc;
        this.hmac = hmac;
        this.clock = clock;
        this.providerClientId = providerClientId;
    }

    @PostMapping(path = SUBMIT_PATH, consumes = "application/json", produces = "application/json")
    ResponseEntity<Map<String, Object>> submit(
            @RequestBody byte[] body,
            @RequestHeader("X-LedgerOps-Key-Id") String keyId,
            @RequestHeader("X-LedgerOps-Timestamp") String timestamp,
            @RequestHeader("X-LedgerOps-Request-Id") String requestId,
            @RequestHeader("X-LedgerOps-Signature") String signature,
            @RequestHeader(value = "traceparent", required = false) String traceparent,
            @RequestHeader(value = "tracestate", required = false) String tracestate) {
        authenticate(SUBMIT_PATH, keyId, timestamp, requestId, body, signature);
        JsonNode request = parse(body);
        validateSubmission(request);
        String providerKey = text(request, "providerIdempotencyKey");
        String intentHash = hash(request, "requestIntentHash");
        String contentHash = sha256(body);
        Map<String, Object> existing = find(providerKey);
        if (existing != null) {
            if (!contentHash.equals(existing.get("requestContentHash"))) {
                throw new SimulatorProblemException(HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "Provider idempotency key was reused with different content");
            }
            return ResponseEntity.ok(existing);
        }
        String scenario = scenario(providerKey);
        if ("TEMPORARY_FAILURE".equals(scenario)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "category", "TEMPORARY_FAILURE", "accepted", false));
        }
        UUID transactionId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        String providerReference = "SIM-" + transactionId;
        String category = category(scenario);
        int inserted = jdbc.update("""
                INSERT INTO simulator.provider_transactions
                    (transaction_id, provider_client_id, provider_idempotency_key,
                     request_intent_hash, request_content_hash, scenario,
                     result_category, provider_result_id, provider_reference,
                     traceparent, tracestate, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (provider_client_id, provider_idempotency_key) DO NOTHING
                """, transactionId, providerClientId, providerKey, intentHash,
                contentHash, scenario, category, resultId, providerReference,
                validTraceparent(traceparent), boundedTracestate(tracestate),
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        if (inserted == 0) {
            Map<String, Object> raced = find(providerKey);
            if (!contentHash.equals(raced.get("requestContentHash"))) {
                throw new SimulatorProblemException(HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT",
                        "Provider idempotency key was reused with different content");
            }
            return ResponseEntity.ok(raced);
        }
        if ("TIMEOUT".equals(scenario) || "TIMEOUT_THEN_SUCCESS".equals(scenario)) {
            sleep(Duration.ofSeconds(6));
        }
        if ("SLOW_RESPONSE".equals(scenario)) sleep(Duration.ofSeconds(4));
        return ResponseEntity.ok(response(transactionId, resultId, providerReference, category));
    }

    @PostMapping(path = STATUS_PATH, consumes = "application/json", produces = "application/json")
    ResponseEntity<Map<String, Object>> status(
            @RequestBody byte[] body,
            @RequestHeader("X-LedgerOps-Key-Id") String keyId,
            @RequestHeader("X-LedgerOps-Timestamp") String timestamp,
            @RequestHeader("X-LedgerOps-Request-Id") String requestId,
            @RequestHeader("X-LedgerOps-Signature") String signature) {
        authenticate(STATUS_PATH, keyId, timestamp, requestId, body, signature);
        Map<String, Object> existing = find(text(parse(body), "providerIdempotencyKey"));
        return existing == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("found", false))
                : ResponseEntity.ok(existing);
    }

    private void authenticate(String path, String keyId, String timestamp,
            String requestId, byte[] body, String signature) {
        if (!hmac.verify("POST", path, keyId, timestamp, requestId, body, signature)) {
            throw new SimulatorProblemException(HttpStatus.UNAUTHORIZED,
                    "INVALID_PROVIDER_SIGNATURE", "Provider request authentication failed");
        }
    }

    private String validTraceparent(String value) {
        return value != null && value.matches(
                "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}") ? value : null;
    }

    private String boundedTracestate(String value) {
        return value == null || value.length() <= 512 ? value : value.substring(0, 512);
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode value = json.readTree(body);
            if (value == null || !value.isObject()) throw new IllegalArgumentException();
            return value;
        } catch (Exception exception) {
            throw new SimulatorProblemException(HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_REQUEST", "Request body must be valid JSON");
        }
    }

    private String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new SimulatorProblemException(HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_REQUEST", field + " is required");
        }
        return value.asString();
    }

    private String hash(JsonNode request, String field) {
        String value = text(request, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new SimulatorProblemException(HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_REQUEST", field + " is invalid");
        }
        return value;
    }

    private void validateSubmission(JsonNode request) {
        uuid(request, "attemptId");
        UUID paymentId = uuid(request, "paymentId");
        JsonNode sequence = request.get("attemptSequence");
        String providerId = text(request, "providerId");
        String providerKey = text(request, "providerIdempotencyKey");
        String amount = text(request, "amount");
        String currency = text(request, "currency");
        text(request, "paymentMethodCategory");
        hash(request, "requestIntentHash");
        if (sequence == null || !sequence.isIntegralNumber() || sequence.intValue() < 1
                || !"SIMULATOR".equals(providerId)
                || !providerKey.equals("payment:" + paymentId.toString().toLowerCase())
                || !amount.matches("^(0|[1-9][0-9]*)(\\.[0-9]+)?$")
                || new java.math.BigDecimal(amount).signum() <= 0
                || !currency.matches("^[A-Z]{3}$")) {
            throw new SimulatorProblemException(HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_REQUEST", "Submission payload is invalid");
        }
    }

    private UUID uuid(JsonNode request, String field) {
        try {
            String value = text(request, field);
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (Exception exception) {
            throw new SimulatorProblemException(HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_REQUEST", field + " must be a canonical UUID");
        }
    }

    private String scenario(String providerKey) {
        return jdbc.query("""
                SELECT scenario FROM simulator.scenario_overrides
                 WHERE provider_client_id = ? AND provider_idempotency_key = ?
                """, rs -> rs.next() ? rs.getString(1) : "SUCCESS", providerClientId, providerKey);
    }

    private Map<String, Object> find(String providerKey) {
        return jdbc.query("""
                SELECT transaction_id, provider_result_id, provider_reference,
                       result_category, request_content_hash
                  FROM simulator.provider_transactions
                 WHERE provider_client_id = ? AND provider_idempotency_key = ?
                """, rs -> rs.next() ? Map.of(
                        "found", true,
                        "providerTransactionId", rs.getObject(1, UUID.class).toString(),
                        "providerResultId", rs.getObject(2, UUID.class).toString(),
                        "providerReference", rs.getString(3),
                        "category", rs.getString(4),
                        "requestContentHash", rs.getString(5)) : null,
                providerClientId, providerKey);
    }

    private Map<String, Object> response(UUID tx, UUID result, String reference, String category) {
        return Map.of("found", true, "providerTransactionId", tx.toString(),
                "providerResultId", result.toString(), "providerReference", reference,
                "category", category);
    }

    private String category(String scenario) {
        return switch (scenario) {
            case "DECLINE" -> "DECLINED";
            case "PERMANENT_FAILURE" -> "PERMANENT_FAILURE";
            case "ACCEPTED" -> "ACCEPTED";
            case "PENDING" -> "PENDING";
            case "TIMEOUT_THEN_SUCCESS", "SUCCESS", "SLOW_RESPONSE", "TIMEOUT",
                 "DELAYED_WEBHOOK", "DUPLICATE_WEBHOOK", "MISSING_WEBHOOK",
                 "OUT_OF_ORDER_WEBHOOK", "INVALID_SIGNATURE", "CONFLICTING_RESULT" ->
                    "SUCCESS";
            default -> throw new IllegalStateException("Unsupported durable scenario " + scenario);
        };
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SimulatorProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                    "SIMULATOR_INTERRUPTED", "Simulator scenario was interrupted");
        }
    }
}
