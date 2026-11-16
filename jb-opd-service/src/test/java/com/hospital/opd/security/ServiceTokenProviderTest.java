package com.hospital.opd.security;

import com.hospital.opd.support.TestJwt;
import com.hospital.opd.util.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTokenProviderTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final ServiceTokenProvider provider = new ServiceTokenProvider(TestJwt.SECRET, clock);

    @Test
    void mintsASystemTokenThatOurOwnFilterAccepts() {
        Claims claims = new JwtTokenProvider(TestJwt.SECRET).parse(provider.current());

        assertThat(claims.getSubject()).isEqualTo(ServiceTokenProvider.SUBJECT);
        assertThat(claims.get("roles", String.class)).isEqualTo("SYSTEM");
        assertThat(claims.getExpiration().toInstant())
                .isAfter(now.get().plus(ServiceTokenProvider.TTL).minusSeconds(2));
    }

    @Test
    void reusesTheTokenUntilAMinuteBeforeExpiry() {
        String first = provider.current();

        now.set(now.get().plusSeconds(150));
        assertThat(provider.current()).isSameAs(first);

        now.set(now.get().plus(ServiceTokenProvider.TTL));
        assertThat(provider.current()).isNotEqualTo(first);
    }

    @Test
    void bearerAddsThePrefix() {
        assertThat(provider.bearer()).startsWith("Bearer ").endsWith(provider.current());
    }
}
