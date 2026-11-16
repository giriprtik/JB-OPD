package com.hospital.opd.resilience;

import com.hospital.opd.exception.DownstreamServiceException;

import java.util.function.Predicate;

/**
 * What counts as a failure for the circuit breakers: the downstream service being down, slow or broken
 * (mapped to 5xx). A 400/409 caused by the caller's own request must not trip the breaker.
 * Anything else (e.g. a bug in our code) is not recorded either.
 */
public class DownstreamFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable t) {
        return t instanceof DownstreamServiceException d
                && d.getClientStatus() != null
                && d.getClientStatus().is5xxServerError();
    }
}
