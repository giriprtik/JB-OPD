package com.hospital.auth.service;

import com.hospital.auth.exception.InvalidCredentialsException;
import com.hospital.auth.model.RefreshToken;
import com.hospital.auth.model.UserAccount;
import com.hospital.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <ul>
 *   <li>Idle timeout ({@code app.refresh.idle-ttl}): each rotation pushes expiry forward (sliding session).</li>
 *   <li>Session cap ({@code app.refresh.session-ttl}): a family can never outlive its first login by more than this.</li>
 *   <li>Reuse detection: presenting a token that was already rotated (outside a short grace window for
 *       two tabs refreshing at once) revokes the whole family.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    static final Duration ROTATION_GRACE = Duration.ofSeconds(10);
    private static final String SESSION_EXPIRED = "Session expired. Please sign in again.";

    private final SecureRandom random = new SecureRandom();
    private final RefreshTokenRepository repo;
    private final Duration idleTtl;
    private final Duration sessionTtl;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(RefreshTokenRepository repo,
                               @Value("${app.refresh.idle-ttl:PT2H}") Duration idleTtl,
                               @Value("${app.refresh.session-ttl:PT12H}") Duration sessionTtl) {
        this(repo, idleTtl, sessionTtl, Clock.systemUTC());
    }

    /** For tests: a fixed clock. */
    RefreshTokenService(RefreshTokenRepository repo, Duration idleTtl, Duration sessionTtl, Clock clock) {
        this.repo = repo;
        this.idleTtl = idleTtl;
        this.sessionTtl = sessionTtl;
        this.clock = clock;
    }

    /** The raw value goes to the cookie; the entity (with its hash) is what was stored. */
    public record Issued(String rawToken, RefreshToken entity) {}

    /** New login: new family. */
    @Transactional
    public Issued startSession(UserAccount user, String userAgent) {
        OffsetDateTime now = now();
        return issue(user, UUID.randomUUID(), now.plus(sessionTtl), userAgent, now);
    }

    /**
     * Swaps the presented token for a new one in the same family.
     * noRollbackFor: on reuse we revoke the family and then throw; the revocation must still commit.
     */
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public Issued rotate(String rawToken, String userAgent) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidCredentialsException(SESSION_EXPIRED);
        }
        OffsetDateTime now = now();
        RefreshToken current = repo.findByTokenHashForUpdate(sha256(rawToken))
                .orElseThrow(() -> new InvalidCredentialsException(SESSION_EXPIRED));

        if (current.isRevoked()) {
            boolean benignRace = RefreshToken.REASON_ROTATED.equals(current.getRevokeReason())
                    && current.getRevokedAt().isAfter(now.minus(ROTATION_GRACE));
            if (!benignRace) {
                int revoked = repo.revokeFamily(current.getFamilyId(), RefreshToken.REASON_REUSE, now);
                log.warn("Refresh token reuse detected for family {} (reason was {}); revoked {} active token(s)",
                        current.getFamilyId(), current.getRevokeReason(), revoked);
                throw new InvalidCredentialsException(SESSION_EXPIRED);
            }
        }

        if (!current.getExpiresAt().isAfter(now) || !current.getSessionEndsAt().isAfter(now)
                || !current.getUser().isEnabled()) {
            throw new InvalidCredentialsException(SESSION_EXPIRED);
        }

        if (!current.isRevoked()) {
            current.setRevokedAt(now);
            current.setRevokeReason(RefreshToken.REASON_ROTATED);
        }
        return issue(current.getUser(), current.getFamilyId(), current.getSessionEndsAt(), userAgent, now);
    }

    /** Logout: ends the whole family, so other tabs of the same login are signed out too. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        repo.findByTokenHashForUpdate(sha256(rawToken))
                .ifPresent(t -> repo.revokeFamily(t.getFamilyId(), RefreshToken.REASON_LOGOUT, now()));
    }

    /** Call when an account is disabled or its password changes. */
    @Transactional
    public int revokeAllForUser(UUID userId) {
        return repo.revokeAllForUser(userId, now());
    }

    /** Housekeeping: drop families whose session ended more than a week ago. */
    @Scheduled(cron = "${app.refresh.purge-cron:0 15 3 * * *}", zone = "Asia/Kathmandu")
    @Transactional
    public void purgeExpired() {
        int deleted = repo.deleteSessionsEndedBefore(now().minusDays(7));
        if (deleted > 0) log.info("Purged {} expired refresh tokens", deleted);
    }

    private Issued issue(UserAccount user, UUID familyId, OffsetDateTime sessionEndsAt,
                         String userAgent, OffsetDateTime now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        OffsetDateTime idleExpiry = now.plus(idleTtl);
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setFamilyId(familyId);
        token.setTokenHash(sha256(raw));
        token.setIssuedAt(now);
        token.setExpiresAt(idleExpiry.isBefore(sessionEndsAt) ? idleExpiry : sessionEndsAt);
        token.setSessionEndsAt(sessionEndsAt);
        token.setUserAgent(truncate(userAgent, 255));
        return new Issued(raw, repo.save(token));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
