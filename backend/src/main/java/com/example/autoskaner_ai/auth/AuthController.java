package com.example.autoskaner_ai.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.register(request.email(), request.password()));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    /**
     * Rotates: the response carries a new refresh token as well as a new access token. The old
     * refresh token is not invalidated — nothing here keeps server-side state — so rotation buys a
     * sliding window rather than revocation. See the change note's "Left undone".
     */
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /**
     * The one authenticated endpoint under this prefix, which is why {@code SecurityConfig} permits
     * the three paths above by name instead of permitting {@code /api/auth/**}.
     *
     * <p>It answers from the token and touches no table: a valid token already carries the email,
     * and its holder has by definition just been verified. The frontend uses it as a cheap "is this
     * session still good".
     *
     * <p>It also reads the principal the way every S-03 endpoint will have to —
     * {@link AuthenticatedUser#requireId} — so that path is exercised before anything depends on it
     * for ownership.
     */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new CurrentUserResponse(
                AuthenticatedUser.requireId(jwt), jwt.getClaimAsString(TokenService.EMAIL_CLAIM));
    }
}
