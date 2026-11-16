package com.hospital.opd.resilience;

import com.hospital.opd.exception.DownstreamServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamFailurePredicateTest {

    private final DownstreamFailurePredicate predicate = new DownstreamFailurePredicate();

    @Test
    void downstreamOutagesCountAsFailures() {
        assertThat(predicate.test(downstream(HttpStatus.SERVICE_UNAVAILABLE))).isTrue();   // unreachable
        assertThat(predicate.test(downstream(HttpStatus.GATEWAY_TIMEOUT))).isTrue();       // timed out
        assertThat(predicate.test(downstream(HttpStatus.BAD_GATEWAY))).isTrue();           // lab returned 500
    }

    @Test
    void theCallersOwnBadRequestDoesNotTripTheBreaker() {
        assertThat(predicate.test(downstream(HttpStatus.BAD_REQUEST))).isFalse();
        assertThat(predicate.test(downstream(HttpStatus.CONFLICT))).isFalse();
    }

    @Test
    void unrelatedExceptionsAreIgnored() {
        assertThat(predicate.test(new IllegalStateException("bug"))).isFalse();
    }

    private static DownstreamServiceException downstream(HttpStatus clientStatus) {
        return new DownstreamServiceException("lab-service", null, clientStatus, "x", null);
    }
}
