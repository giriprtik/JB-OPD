package com.hospital.auth.service;

import com.hospital.auth.exception.InvalidCredentialsException;
import com.hospital.auth.model.RefreshToken;
import com.hospital.auth.model.UserAccount;
import com.hospital.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T04:00:00Z");
    private static final OffsetDateTime NOW_ODT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
    private final RefreshTokenService service = new RefreshTokenService(
            repo, Duration.ofHours(2), Duration.ofHours(12), Clock.fixed(NOW, ZoneOffset.UTC));

    private UserAccount nurse;

    @BeforeEach
    void setUp() {
        when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        nurse = new UserAccount();
        nurse.setId(UUID.randomUUID());
        nurse.setUsername("nurse");
        nurse.setRole("NURSE");
        nurse.setEnabled(true);
    }

    @Nested
    @DisplayName("startSession")
    class StartSession {

        @Test
        void storesOnlyTheHashAndCapsExpiryAtTheIdleTimeout() {
            RefreshTokenService.Issued issued = service.startSession(nurse, "Firefox");

            RefreshToken stored = issued.entity();
            assertThat(issued.rawToken()).hasSizeGreaterThanOrEqualTo(43);   // 32 random bytes, base64url
            assertThat(stored.getTokenHash()).isEqualTo(RefreshTokenService.sha256(issued.rawToken()))
                    .isNotEqualTo(issued.rawToken()).hasSize(64);
            assertThat(stored.getExpiresAt()).isEqualTo(NOW_ODT.plusHours(2));
            assertThat(stored.getSessionEndsAt()).isEqualTo(NOW_ODT.plusHours(12));
            assertThat(stored.getFamilyId()).isNotNull();
            assertThat(stored.getUserAgent()).isEqualTo("Firefox");
            assertThat(stored.isRevoked()).isFalse();
        }

        @Test
        void twoLoginsGetDifferentTokensAndFamilies() {
            RefreshTokenService.Issued a = service.startSession(nurse, null);
            RefreshTokenService.Issued b = service.startSession(nurse, null);

            assertThat(a.rawToken()).isNotEqualTo(b.rawToken());
            assertThat(a.entity().getFamilyId()).isNotEqualTo(b.entity().getFamilyId());
        }

        @Test
        void aVeryLongUserAgentIsTruncatedToTheColumnSize() {
            RefreshTokenService.Issued issued = service.startSession(nurse, "x".repeat(400));

            assertThat(issued.entity().getUserAgent()).hasSize(255);
        }
    }

    @Nested
    @DisplayName("rotate")
    class Rotate {

        @Test
        void issuesANewTokenInTheSameFamilyAndRetiresTheOldOne() {
            RefreshToken current = stored("old", NOW_ODT.plusHours(1), NOW_ODT.plusHours(10));

            RefreshTokenService.Issued next = service.rotate("old", "ua");

            assertThat(current.getRevokedAt()).isEqualTo(NOW_ODT);
            assertThat(current.getRevokeReason()).isEqualTo(RefreshToken.REASON_ROTATED);
            assertThat(next.entity().getFamilyId()).isEqualTo(current.getFamilyId());
            assertThat(next.entity().getUser()).isSameAs(nurse);
            assertThat(next.rawToken()).isNotEqualTo("old");
        }

        @Test
        void slidingExpiryNeverPassesTheSessionCap() {
            stored("old", NOW_ODT.plusMinutes(30), NOW_ODT.plusMinutes(45));

            RefreshTokenService.Issued next = service.rotate("old", "ua");

            assertThat(next.entity().getExpiresAt()).isEqualTo(NOW_ODT.plusMinutes(45));   // not now + 2h
            assertThat(next.entity().getSessionEndsAt()).isEqualTo(NOW_ODT.plusMinutes(45));
        }

        @Test
        void anUnknownTokenIsRejected() {
            when(repo.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rotate("made-up", "ua")).isInstanceOf(InvalidCredentialsException.class);
            verify(repo, never()).save(any());
        }

        @Test
        void aMissingCookieIsRejectedWithoutTouchingTheDatabase() {
            assertThatThrownBy(() -> service.rotate(null, "ua")).isInstanceOf(InvalidCredentialsException.class);
            assertThatThrownBy(() -> service.rotate("  ", "ua")).isInstanceOf(InvalidCredentialsException.class);
            verify(repo, never()).findByTokenHashForUpdate(anyString());
        }

        @Test
        void anIdleExpiredTokenIsRejected() {
            stored("old", NOW_ODT.minusSeconds(1), NOW_ODT.plusHours(5));

            assertThatThrownBy(() -> service.rotate("old", "ua")).isInstanceOf(InvalidCredentialsException.class);
            verify(repo, never()).save(any());
        }

        @Test
        void aTokenPastTheSessionCapIsRejected() {
            stored("old", NOW_ODT.plusHours(1), NOW_ODT.minusSeconds(1));

            assertThatThrownBy(() -> service.rotate("old", "ua")).isInstanceOf(InvalidCredentialsException.class);
        }

        @Test
        void aDisabledAccountCannotRefresh() {
            nurse.setEnabled(false);
            stored("old", NOW_ODT.plusHours(1), NOW_ODT.plusHours(5));

            assertThatThrownBy(() -> service.rotate("old", "ua")).isInstanceOf(InvalidCredentialsException.class);
            verify(repo, never()).save(any());
        }

        @Test
        void replayingARotatedTokenAfterTheGraceWindowRevokesTheWholeFamily() {
            RefreshToken current = stored("old", NOW_ODT.plusHours(1), NOW_ODT.plusHours(5));
            current.setRevokedAt(NOW_ODT.minus(RefreshTokenService.ROTATION_GRACE).minusSeconds(1));
            current.setRevokeReason(RefreshToken.REASON_ROTATED);

            assertThatThrownBy(() -> service.rotate("old", "ua")).isInstanceOf(InvalidCredentialsException.class);
            verify(repo).revokeFamily(current.getFamilyId(), RefreshToken.REASON_REUSE, NOW_ODT);
            verify(repo, never()).save(any());
        }

        @Test
        void twoTabsRefreshingWithinTheGraceWindowIsNotTreatedAsTheft() {
            RefreshToken current = stored("old", NOW_ODT.plusHours(1), NOW_ODT.plusHours(5));
            current.setRevokedAt(NOW_ODT.minusSeconds(3));
            current.setRevokeReason(RefreshToken.REASON_ROTATED);

            RefreshTokenService.Issued next = service.rotate("old", "ua");

            assertThat(next.entity().getFamilyId()).isEqualTo(current.getFamilyId());
            verify(repo, never()).revokeFamily(any(), anyString(), any());
        }

        @Test
        void aLoggedOutTokenIsNeverAcceptedEvenInsideTheGraceWindow() {
            RefreshToken current = stored("old", NOW_ODT.plusHours(1), NOW_ODT.plusHours(5));
            current.setRevokedAt(NOW_ODT.minusSeconds(1));
            current.setRevokeReason(RefreshToken.REASON_LOGOUT);

            assertThatThrownBy(() -> service.rotate("old", "ua")).isInstanceOf(InvalidCredentialsException.class);
            verify(repo).revokeFamily(current.getFamilyId(), RefreshToken.REASON_REUSE, NOW_ODT);
        }
    }

    @Test
    void logoutRevokesTheWholeFamily() {
        RefreshToken current = stored("raw", NOW_ODT.plusHours(1), NOW_ODT.plusHours(5));

        service.revoke("raw");

        verify(repo).revokeFamily(current.getFamilyId(), RefreshToken.REASON_LOGOUT, NOW_ODT);
    }

    @Test
    void logoutWithoutACookieDoesNothing() {
        service.revoke(null);

        verify(repo, never()).findByTokenHashForUpdate(anyString());
    }

    @Test
    void purgeDeletesFamiliesWhoseSessionEndedOverAWeekAgo() {
        service.purgeExpired();

        verify(repo).deleteSessionsEndedBefore(eq(NOW_ODT.minusDays(7)));
    }

    // ---------------------------------------------------------------- helpers

    private RefreshToken stored(String raw, OffsetDateTime expiresAt, OffsetDateTime sessionEndsAt) {
        RefreshToken t = new RefreshToken();
        t.setId(UUID.randomUUID());
        t.setUser(nurse);
        t.setFamilyId(UUID.randomUUID());
        t.setTokenHash(RefreshTokenService.sha256(raw));
        t.setIssuedAt(NOW_ODT.minusHours(1));
        t.setExpiresAt(expiresAt);
        t.setSessionEndsAt(sessionEndsAt);
        when(repo.findByTokenHashForUpdate(RefreshTokenService.sha256(raw))).thenReturn(Optional.of(t));
        return t;
    }
}
