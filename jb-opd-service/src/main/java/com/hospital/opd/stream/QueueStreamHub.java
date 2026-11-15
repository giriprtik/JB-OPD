package com.hospital.opd.stream;

import com.hospital.opd.event.VisitChanged;
import com.hospital.opd.repository.VisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the open Server-Sent Event streams and pushes "visit changed" notices to them.
 * Doctors only hear about their own patients; nurses and admins hear about every visit.
 * Single-instance design (in-memory registry): with several OPD replicas, fan out via Postgres LISTEN/NOTIFY.
 */
@Component
public class QueueStreamHub {

    private static final Logger log = LoggerFactory.getLogger(QueueStreamHub.class);
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);   // the browser reconnects on its own
    static final int MAX_STREAMS = 500;

    record Subscriber(String username, boolean doctor) {}

    private final Map<SseEmitter, Subscriber> streams = new ConcurrentHashMap<>();
    private final VisitRepository visitRepository;

    public QueueStreamHub(VisitRepository visitRepository) {
        this.visitRepository = visitRepository;
    }

    public SseEmitter subscribe(String username, boolean doctor) {
        if (streams.size() >= MAX_STREAMS) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Too many live connections; falling back to polling");
        }
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        streams.put(emitter, new Subscriber(username, doctor));
        emitter.onCompletion(() -> streams.remove(emitter));
        emitter.onTimeout(() -> { streams.remove(emitter); emitter.complete(); });
        emitter.onError(e -> streams.remove(emitter));
        send(emitter, SseEmitter.event().name("ready").data("ok"));   // first byte now: proxies see a live stream
        return emitter;
    }

    /**
     * Runs after the status change commits, so a screen never re-fetches before the data is visible.
     * fallbackExecution: some callers (lab sync) record events outside a transaction.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVisitChanged(VisitChanged event) {
        if (streams.isEmpty()) return;
        Optional<String> assignedDoctor = streams.values().stream().anyMatch(Subscriber::doctor)
                ? visitRepository.findAssignedDoctorUsername(event.visitId())
                : Optional.empty();

        streams.forEach((emitter, sub) -> {
            boolean relevant = !sub.doctor() || assignedDoctor.map(sub.username()::equals).orElse(false);
            if (relevant) send(emitter, SseEmitter.event().name("visit").data(event));
        });
    }

    /** A comment line every 25 s keeps nginx and other proxies from closing an idle stream. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        streams.keySet().forEach(emitter -> send(emitter, SseEmitter.event().comment("hb")));
    }

    int openStreams() {
        return streams.size();
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException ex) {
            // Client went away; drop the stream quietly.
            streams.remove(emitter);
            log.debug("Dropped a closed SSE stream: {}", ex.getMessage());
        }
    }
}
