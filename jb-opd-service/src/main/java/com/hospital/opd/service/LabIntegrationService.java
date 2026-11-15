package com.hospital.opd.service;

import com.hospital.opd.dto.LabOrderSummary;
import com.hospital.opd.dto.LabResultItem;
import com.hospital.opd.exception.DownstreamServiceException;
import com.hospital.opd.outbox.LabOrderPayload;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class LabIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(LabIntegrationService.class);
    private static final String SERVICE = "lab-service";
    /** Resilience4j instance name (see resilience4j.* in application.properties). */
    public static final String LAB = "lab";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient labWebClient;

    public LabIntegrationService(WebClient labWebClient) {
        this.labWebClient = labWebClient;
    }

    /**
     * Creates a lab order and returns its ID. Idempotent on payload.requestId: delivering the same payload
     * twice returns the same order (the lab answers 200 instead of 201). Called by the outbox relay with
     * the service token; the payload's field names match the lab's LabOrderRequest.
     */
    @Bulkhead(name = LAB)
    @CircuitBreaker(name = LAB)
    public Long raiseLabInvestigationOrder(LabOrderPayload payload, String authorization) {
        LabOrderSummary created = execute(() -> labWebClient.post()
                .uri("/api/v1/lab/orders")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(LabOrderSummary.class));

        if (created == null || created.id() == null) {
            throw new DownstreamServiceException(SERVICE, null, HttpStatus.BAD_GATEWAY,
                    "Lab service did not return an order ID", null);
        }
        return created.id();
    }

    /** Fetches orders by ID in one call. */
    @Bulkhead(name = LAB)
    @CircuitBreaker(name = LAB)
    public List<LabOrderSummary> getOrders(Collection<Long> ids, String authorization) {
        if (ids.isEmpty()) return List.of();
        String csv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        LabOrderSummary[] orders = execute(() -> labWebClient.get()
                .uri(b -> b.path("/api/v1/lab/orders").queryParam("ids", csv).build())
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve()
                .bodyToMono(LabOrderSummary[].class));
        return orders == null ? List.of() : List.of(orders);
    }

    /** Fetches all result rows for an order. */
    @Bulkhead(name = LAB)
    @CircuitBreaker(name = LAB)
    public List<LabResultItem> getResults(Long orderId, String authorization) {
        LabResultItem[] results = execute(() -> labWebClient.get()
                .uri("/api/v1/lab/results/order/{id}", orderId)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve()
                .bodyToMono(LabResultItem[].class));
        return results == null ? List.of() : List.of(results);
    }

    private <T> T execute(Supplier<Mono<T>> call) {
        try {
            return call.get().block(TIMEOUT);
        } catch (WebClientResponseException ex) {
            int s = ex.getStatusCode().value();
            log.warn("Lab service call failed: {} {}", s, ex.getResponseBodyAsString());
            HttpStatus clientStatus = switch (s) {
                case 400, 409, 422 -> HttpStatus.valueOf(s);
                default -> HttpStatus.BAD_GATEWAY;
            };
            String msg = switch (s) {
                case 401 -> "Lab service rejected the token";
                case 403 -> "Lab service denied the request";
                default -> "Lab service returned " + s;
            };
            throw new DownstreamServiceException(SERVICE, s, clientStatus, msg, ex);
        } catch (WebClientRequestException ex) {
            throw new DownstreamServiceException(SERVICE, null, HttpStatus.SERVICE_UNAVAILABLE,
                    "Lab service unreachable", ex);
        } catch (IllegalStateException ex) { // block(timeout)
            throw new DownstreamServiceException(SERVICE, null, HttpStatus.GATEWAY_TIMEOUT,
                    "Lab service timed out", ex);
        }
    }
}