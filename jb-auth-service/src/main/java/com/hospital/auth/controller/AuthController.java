package com.hospital.auth.controller;

import com.hospital.auth.config.RefreshCookieFactory;
import com.hospital.auth.dto.LoginRequest;
import com.hospital.auth.dto.LoginResponse;
import com.hospital.auth.dto.StaffSummary;
import com.hospital.auth.dto.UserRegisterRequest;
import com.hospital.auth.exception.InvalidCredentialsException;
import com.hospital.auth.model.UserAccount;
import com.hospital.auth.service.AuthService;
import com.hospital.auth.service.RefreshTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** Custom header the UI sends on refresh. Cross-site forms and <img> tags cannot set it (CSRF guard). */
    public static final String REFRESH_MARKER_HEADER = "X-JB-Refresh";

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshCookieFactory cookies;

    public AuthController(AuthService authService,
                          RefreshTokenService refreshTokenService,
                          RefreshCookieFactory cookies) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.cookies = cookies;
    }

    /** Access token in the body (kept in memory by the UI); refresh token only in the HttpOnly cookie. */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        AuthService.LoginResult result = authService.authenticateUser(request, userAgent);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.create(result.refreshToken()).toString())
                .body(result.response());
    }

    /** Swaps the refresh cookie for a new one and returns a fresh access token. */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = RefreshCookieFactory.NAME, required = false) String rawRefreshToken,
            @RequestHeader(value = REFRESH_MARKER_HEADER, required = false) String marker,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        if (!"1".equals(marker)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ProblemDetail.forStatusAndDetail(
                    HttpStatus.FORBIDDEN, "Missing " + REFRESH_MARKER_HEADER + " header"));
        }
        try {
            AuthService.LoginResult result = authService.refresh(rawRefreshToken, userAgent);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookies.create(result.refreshToken()).toString())
                    .body(result.response());
        } catch (InvalidCredentialsException ex) {
            // Also drop the dead cookie so the browser stops sending it.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage()));
        }
    }

    /** Ends the whole login (all tabs) and clears the cookie. Always 204, even without a cookie. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookieFactory.NAME, required = false) String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    /** Staff accounts are created by an administrator; there is no public sign-up. */
    @PostMapping("/register")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody UserRegisterRequest request) {
        UserAccount created = authService.registerNewUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "username", created.getUsername(),
                "role", created.getRole()));
    }

    @GetMapping("/profile")
    public StaffSummary profile(@AuthenticationPrincipal UserAccount user) {
        return authService.resolveUser(user.getUsername());
    }

    /** Doctor picker for the registration desk. */
    @GetMapping("/doctors")
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR', 'ADMIN')")
    public List<StaffSummary> doctors() {
        return authService.listActiveDoctors();
    }

    /** Internal (service-to-service, X-Internal-Secret): full staff lookup. */
    @GetMapping("/users/resolve")
    @PreAuthorize("hasRole('SYSTEM')")
    public StaffSummary resolve(@RequestParam("username") String username) {
        return authService.resolveUser(username);
    }

    /** Internal (service-to-service, X-Internal-Secret): kept for backwards compatibility. */
    @GetMapping("/users/resolve-id")
    @PreAuthorize("hasRole('SYSTEM')")
    public UUID resolveId(@RequestParam("username") String username) {
        return authService.resolveUser(username).id();
    }
}
