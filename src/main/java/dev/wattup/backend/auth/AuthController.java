package dev.wattup.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
class AuthController {
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private final AuthAccounts accounts;
    private final AuthSessions sessions;
    private final AuthEmail email;
    private final SocialIdentityVerifier social;
    private final AuthRateLimit rate;
    private final AuthMail mail;
    private final SocialProofGuard proof;

    AuthController(AuthAccounts accounts, AuthSessions sessions, AuthEmail email, SocialIdentityVerifier social,
                   AuthRateLimit rate, AuthMail mail, SocialProofGuard proof) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.email = email;
        this.social = social;
        this.rate = rate;
        this.mail = mail;
        this.proof = proof;
    }

    @GetMapping("/api/auth/agreements")
    Map<String, List<AuthAccounts.Agreement>> agreements() {
        return Map.of("agreements", accounts.agreements());
    }

    @PostMapping("/api/auth/social/kakao")
    ResponseEntity<?> kakao(@RequestBody Kakao request, HttpServletRequest servlet) {
        return socialLogin("KAKAO", request.providerAccessToken, servlet);
    }

    @PostMapping("/api/auth/social/google")
    ResponseEntity<?> google(@RequestBody Google request, HttpServletRequest servlet) {
        return socialLogin("GOOGLE", request.idToken, servlet);
    }

    private ResponseEntity<?> socialLogin(String provider, String token, HttpServletRequest servlet) {
        ipLimit("social", servlet);
        var startedAt = proof.now();
        SocialIdentityVerifier.Identity identity;
        try {
            identity = social.verify(provider, token);
        } catch (SocialIdentityVerifier.Failure failure) {
            throw failure.unavailable() ? new AuthFailure(502, "UPSTREAM_UNAVAILABLE")
                    : new AuthFailure(401, "INVALID_PROVIDER_TOKEN");
        }
        rate.hit("social-identity", provider + "|" + identity.subject(), 12, TEN_MINUTES, "RATE_LIMITED");
        var result = accounts.social(identity, startedAt);
        return result.login() == null ? ResponseEntity.accepted().body(result.registration())
                : ResponseEntity.ok(result.login());
    }

    @PostMapping("/api/auth/social/signup")
    ResponseEntity<AuthAccounts.Created> socialSignup(@RequestBody SocialSignup request, HttpServletRequest servlet) {
        ipLimit("signup", servlet);
        return ResponseEntity.status(201).body(accounts.socialSignup(request.registrationToken, request.nickname,
                request.agreements));
    }

    @PostMapping("/api/auth/refresh")
    AuthSessions.Tokens refresh(@RequestBody Refresh request, HttpServletRequest servlet) {
        rate.hit("refresh-ip", servlet.getRemoteAddr(), 240, MINUTE, "RATE_LIMITED");
        return sessions.refresh(request.refreshToken);
    }

    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt access, @RequestBody Refresh request) {
        sessions.logout(access, request.refreshToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/auth/logout-all")
    ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Jwt access) {
        sessions.logoutAll(access);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/auth/email/verifications")
    ResponseEntity<VerificationAccepted> verification(@RequestBody EmailPurpose request, HttpServletRequest servlet) {
        requireSignupPurpose(request.purpose);
        String address = AuthValues.email(request.email);
        ipLimit("verification", servlet);
        rate.hit("verification-email", address, 1, MINUTE, "RATE_LIMITED");
        mail.requireConfigured();
        String code = email.beginVerification(address);
        if (code != null) {
            mail.verification(address, code);
        }
        return ResponseEntity.accepted().body(new VerificationAccepted("인증 요청을 접수했습니다.", 60));
    }

    @PostMapping("/api/auth/email/verifications/confirm")
    AuthEmail.Verification verifyEmail(@RequestBody Confirmation request, HttpServletRequest servlet) {
        requireSignupPurpose(request.purpose);
        String address = AuthValues.email(request.email);
        ipLimit("verify-email", servlet);
        rate.hit("verify-address", address, 10, TEN_MINUTES, "TOO_MANY_ATTEMPTS");
        return email.confirmVerification(address, request.code);
    }

    @PostMapping("/api/auth/signup/email")
    ResponseEntity<AuthAccounts.Created> emailSignup(@RequestBody EmailSignup request, HttpServletRequest servlet) {
        ipLimit("signup", servlet);
        return ResponseEntity.status(201).body(email.signup(AuthValues.email(request.email), request.password,
                request.verificationToken, request.nickname, request.agreements));
    }

    @PostMapping("/api/auth/login/email")
    AuthAccounts.Login emailLogin(@RequestBody EmailLogin request, HttpServletRequest servlet) {
        ipLimit("login-email", servlet);
        String address = AuthValues.email(request.email);
        rate.hit("email-login", address, 10, TEN_MINUTES, "TOO_MANY_ATTEMPTS");
        return email.login(address, request.password);
    }

    @PostMapping("/api/auth/password-resets")
    ResponseEntity<Map<String, String>> beginReset(@RequestBody ResetEmail request, HttpServletRequest servlet) {
        String address = AuthValues.email(request.email);
        ipLimit("reset", servlet);
        rate.hit("reset-email", address, 1, MINUTE, "RATE_LIMITED");
        mail.requireConfigured();
        String token = email.beginReset(address);
        if (token != null) {
            mail.reset(address, token);
        }
        return ResponseEntity.accepted().body(Map.of("message", "해당 주소로 안내할 수 있는 경우 재설정 메일을 발송합니다."));
    }

    @PostMapping("/api/auth/password-resets/confirm")
    ResponseEntity<Void> confirmReset(@RequestBody ResetPassword request, HttpServletRequest servlet) {
        ipLimit("reset-confirm", servlet);
        email.reset(request.resetToken, request.newPassword);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/api/users/me/password")
    ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt access, @RequestBody ChangePassword request) {
        rate.hit("password-change", access.getSubject(), 10, TEN_MINUTES, "TOO_MANY_ATTEMPTS");
        email.change(access, request.currentPassword, request.newPassword);
        return ResponseEntity.noContent().build();
    }

    private void ipLimit(String scope, HttpServletRequest servlet) {
        rate.hit(scope + "-ip", servlet.getRemoteAddr(), 30, MINUTE, "RATE_LIMITED");
    }

    private static void requireSignupPurpose(String purpose) {
        if (!"SIGNUP".equals(purpose)) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
    }

    record Kakao(String providerAccessToken) {}
    record Google(String idToken) {}
    record Refresh(String refreshToken) {}
    record SocialSignup(String registrationToken, String nickname, List<AuthAccounts.Consent> agreements) {}
    record EmailPurpose(String email, String purpose) {}
    record Confirmation(String email, String code, String purpose) {}
    record EmailSignup(String email, String password, String nickname, String verificationToken,
                       List<AuthAccounts.Consent> agreements) {}
    record EmailLogin(String email, String password) {}
    record ResetEmail(String email) {}
    record ResetPassword(String resetToken, String newPassword) {}
    record ChangePassword(String currentPassword, String newPassword) {}
    record VerificationAccepted(String message, int retryAfterSeconds) {}
}
