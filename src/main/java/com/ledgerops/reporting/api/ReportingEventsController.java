package com.ledgerops.reporting.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reports/events")
class ReportingEventsController {

    private static final long RECONNECT_DELAY_MILLIS = 3_000;
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final ReportingProjectionEventQuery events;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    @Autowired
    ReportingEventsController(ReportingProjectionEventQuery events) {
        this(events, Clock.systemUTC(), Executors.newScheduledThreadPool(
                4, namedDaemonThreads("ledgerops-reporting-sse-")));
    }

    ReportingEventsController(
            ReportingProjectionEventQuery events,
            Clock clock,
            ScheduledExecutorService scheduler
    ) {
        this.events = events;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(
            @PathVariable UUID tenantId,
            @RequestParam(name = "merchantId", required = false) List<String> merchantIds,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader,
            HttpServletRequest request
    ) {
        AuthorizedRequestContext authorization = ReportingTenantAuthorization.required(
                tenantId, merchantIds, request);
        Set<UUID> effectiveMerchantIds = ReportingTenantAuthorization.effectiveMerchantIds(
                authorization, merchantIds);
        long lastEventId = parseLastEventId(lastEventIdHeader);
        SseEmitter emitter = new SseEmitter(0L);
        StreamState state = new StreamState(
                emitter, tenantId, effectiveMerchantIds, lastEventId, Instant.now(clock));
        AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();
        Runnable cancel = () -> {
            ScheduledFuture<?> future = task.get();
            if (future != null) {
                future.cancel(false);
            }
            state.closed = true;
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());

        try {
            emitter.send(SseEmitter.event().reconnectTime(RECONNECT_DELAY_MILLIS));
        } catch (IOException exception) {
            cancel.run();
            emitter.completeWithError(exception);
            return response(emitter);
        }

        task.set(scheduler.scheduleAtFixedRate(
                () -> poll(state, cancel),
                0,
                POLL_INTERVAL.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS));
        return response(emitter);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void poll(StreamState state, Runnable cancel) {
        if (state.closed) {
            return;
        }
        try {
            ReportingProjectionEventReplay replay = events.replayAfter(
                    state.tenantId, state.lastEventId, state.merchantIds);
            if (replay.resyncRequired()) {
                state.emitter.send(SseEmitter.event()
                        .name("resync-required")
                        .data(new ResyncRequiredPayload("CURSOR_UNAVAILABLE")));
                state.emitter.complete();
                cancel.run();
                return;
            }

            for (ReportingProjectionEvent event : replay.events()) {
                state.emitter.send(SseEmitter.event()
                        .id(Long.toString(event.eventId()))
                        .name("projection-updated")
                        .data(new ProjectionUpdatedPayload(
                                event.generation(),
                                event.affectedInWireOrder().stream().map(Enum::name).toList(),
                                event.occurredAt())));
                state.lastEventId = event.eventId();
                state.lastHeartbeatAt = Instant.now(clock);
            }

            Instant now = Instant.now(clock);
            if (!now.isBefore(state.lastHeartbeatAt.plus(HEARTBEAT_INTERVAL))) {
                state.emitter.send(SseEmitter.event().comment("keepalive"));
                state.lastHeartbeatAt = now;
            }
        } catch (IOException | RuntimeException exception) {
            state.emitter.completeWithError(exception);
            cancel.run();
        }
    }

    private static ResponseEntity<SseEmitter> response(SseEmitter emitter) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        headers.setCacheControl(CacheControl.noCache().noTransform());
        headers.add("X-Accel-Buffering", "no");
        return ResponseEntity.ok().headers(headers).body(emitter);
    }

    private static long parseLastEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID must be a non-negative integer", exception);
        }
    }

    private static ThreadFactory namedDaemonThreads(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + thread.threadId());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class StreamState {
        private final SseEmitter emitter;
        private final UUID tenantId;
        private final Set<UUID> merchantIds;
        private long lastEventId;
        private Instant lastHeartbeatAt;
        private volatile boolean closed;

        private StreamState(
                SseEmitter emitter,
                UUID tenantId,
                Set<UUID> merchantIds,
                long lastEventId,
                Instant lastHeartbeatAt
        ) {
            this.emitter = emitter;
            this.tenantId = tenantId;
            this.merchantIds = merchantIds;
            this.lastEventId = lastEventId;
            this.lastHeartbeatAt = lastHeartbeatAt;
        }
    }

    private record ProjectionUpdatedPayload(
            long generation,
            List<String> affected,
            Instant occurredAt
    ) {
    }

    private record ResyncRequiredPayload(String reason) {
    }
}
