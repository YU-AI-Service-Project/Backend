package dev.wattup.backend.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/users/me")
class AccountController {
    private final AccountProfile profile;
    private final AccountActions actions;
    private final SocialIdentityVerifier verifier;
    private final AuthRateLimit rate;
    private final SocialProofGuard proof;
    private final Semaphore disconnects = new Semaphore(2);

    AccountController(AccountProfile profile, AccountActions actions, SocialIdentityVerifier verifier, AuthRateLimit rate,
                      SocialProofGuard proof) {
        this.profile = profile;
        this.actions = actions;
        this.verifier = verifier;
        this.rate = rate;
        this.proof = proof;
    }

    @GetMapping
    AccountProfile.UserSummary me(@AuthenticationPrincipal Jwt access) {
        return profile.summary(access);
    }

    @PatchMapping
    AccountProfile.UserSummary rename(@AuthenticationPrincipal Jwt access, @RequestBody JsonNode input) {
        if (!input.isObject() || input.size() != 1 || !input.has("nickname")) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        return profile.rename(access, input.path("nickname").isString() ? input.path("nickname").asText() : null);
    }

    @PostMapping("/reauthentication")
    AccountActions.Reauthentication reauth(@AuthenticationPrincipal Jwt access, @RequestBody Reauth input) {
        sensitiveLimit("reauth", access);
        var action = action(input.purpose);
        if ("PASSWORD".equals(input.method)) {
            return actions.reauthenticate(access, action, input.password, null, null);
        }
        if (!"KAKAO".equals(input.method) && !"GOOGLE".equals(input.method)) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        var startedAt = proof.now();
        return actions.reauthenticate(access, action, null, verify(input.method, input.providerAccessToken, input.idToken),
                startedAt);
    }

    @DeleteMapping
    ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt access, @RequestBody Withdrawal input) {
        if (!"WITHDRAW".equals(input.confirmation)) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        disconnect(() -> actions.withdraw(access, input.reauthenticationToken));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/social-accounts")
    Map<String, List<AccountProfile.SocialAccount>> socialAccounts(@AuthenticationPrincipal Jwt access) {
        return Map.of("items", profile.socialAccounts(AuthSessions.userId(access)));
    }

    @PostMapping("/social-accounts/{provider}")
    ResponseEntity<Map<String, List<AccountProfile.SocialAccount>>> link(@AuthenticationPrincipal Jwt access,
            @PathVariable String provider, @RequestBody Link input) {
        sensitiveLimit("link-social", access);
        var startedAt = proof.now();
        var identity = verify(provider(provider), input.providerAccessToken, input.idToken);
        return ResponseEntity.status(201).body(Map.of("items",
                actions.link(access, input.reauthenticationToken, identity, startedAt)));
    }

    @DeleteMapping("/social-accounts/{provider}")
    ResponseEntity<Void> unlink(@AuthenticationPrincipal Jwt access, @PathVariable String provider,
                               @RequestBody Confirmation input) {
        disconnect(() -> actions.unlink(access, provider(provider), input.reauthenticationToken));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/agreements")
    Map<String, List<AccountProfile.AgreementDecision>> agreements(@AuthenticationPrincipal Jwt access) {
        return Map.of("items", profile.agreements(access));
    }

    @PatchMapping("/agreements")
    AccountProfile.AgreementDecision changeAgreement(@AuthenticationPrincipal Jwt access, @RequestBody ConsentUpdate input) {
        return profile.changeAgreement(access, input.type, input.agreed);
    }

    @GetMapping("/sessions")
    Map<String, List<AccountProfile.DeviceSession>> sessions(@AuthenticationPrincipal Jwt access) {
        return Map.of("items", profile.sessions(access));
    }

    @DeleteMapping("/sessions/{sessionId}")
    ResponseEntity<Void> logoutSession(@AuthenticationPrincipal Jwt access, @PathVariable String sessionId) {
        UUID family;
        try {
            family = UUID.fromString(sessionId);
            if (!family.toString().equalsIgnoreCase(sessionId)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new AuthFailure(404, "SESSION_NOT_FOUND");
        }
        profile.logoutSession(access, family);
        return ResponseEntity.noContent().build();
    }

    private void sensitiveLimit(String scope, Jwt access) {
        rate.hit(scope, access.getSubject(), 10, Duration.ofMinutes(10), "TOO_MANY_ATTEMPTS");
    }

    private void disconnect(Runnable action) {
        if (!disconnects.tryAcquire()) {
            throw new AuthFailure(503, "AUTH_UNAVAILABLE");
        }
        try {
            action.run();
        } finally {
            disconnects.release();
        }
    }

    private SocialIdentityVerifier.Identity verify(String provider, String kakaoToken, String googleToken) {
        try {
            return verifier.verify(provider, "KAKAO".equals(provider) ? kakaoToken : googleToken);
        } catch (SocialIdentityVerifier.Failure e) {
            throw e.unavailable() ? new AuthFailure(502, "UPSTREAM_UNAVAILABLE")
                    : new AuthFailure(401, "INVALID_PROVIDER_TOKEN");
        }
    }

    private static String provider(String value) {
        return switch (value) {
            case "kakao" -> "KAKAO";
            case "google" -> "GOOGLE";
            default -> throw new AuthFailure(400, "INVALID_REQUEST");
        };
    }

    private static AccountActions.Action action(String value) {
        if (value != null) {
            try {
                return AccountActions.Action.valueOf(value);
            } catch (IllegalArgumentException ignored) {
            }
        }
        throw new AuthFailure(400, "INVALID_REQUEST");
    }

    record Reauth(String purpose, String method, String password, String providerAccessToken, String idToken) {}
    record Withdrawal(String reauthenticationToken, String confirmation) {}
    record Link(String providerAccessToken, String idToken, String reauthenticationToken) {}
    record Confirmation(String reauthenticationToken) {}
    record ConsentUpdate(String type, Boolean agreed) {}
}
