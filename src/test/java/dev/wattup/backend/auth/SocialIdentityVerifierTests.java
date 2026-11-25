package dev.wattup.backend.auth;

import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SocialIdentityVerifierTests {
    private static final String KAKAO = "https://kapi.kakao.com";
    private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final String GOOGLE_CLIENT = "test-client.apps.googleusercontent.com";
    private static final String TOKEN = "test-token";
    private static RSAKey key;
    private static RSAKey otherKey;

    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final SocialIdentityVerifier verifier = new SocialIdentityVerifier(new JsonMapper(), "73", GOOGLE_CLIENT, http);

    @BeforeAll
    static void generateTestKeys() throws JOSEException {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    }

    @Test
    void providerHttpClientDoesNotFollowRedirectsAndRejectsAnOversizedResponse() throws Exception {
        var secondEndpointCalls = new AtomicInteger();
        var local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/redirect", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/payload");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        local.createContext("/payload", exchange -> {
            secondEndpointCalls.incrementAndGet();
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders("/payload/error".equals(exchange.getRequestURI().getPath()) ? 400 : 200, 0);
                exchange.getResponseBody().write(" ".repeat(300_000).getBytes(StandardCharsets.UTF_8));
            }
        });
        local.start();
        try {
            var client = SocialIdentityVerifier.client();
            String base = "http://127.0.0.1:" + local.getAddress().getPort();
            var redirect = client.getForEntity(base + "/redirect", String.class);
            assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(secondEndpointCalls.get()).isZero();
            for (String path : new String[]{"/payload", "/payload/error"}) {
                assertThatThrownBy(() -> client.getForEntity(base + path, String.class))
                        .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
                        .hasMessageContaining("Identity provider response exceeded the configured size");
            }
        } finally {
            local.stop(0);
        }
    }

    @Test
    void verifiesKakaoAppAndSubjectBeforeReadingOptionalProfile() {
        kakaoTokenInfo(73);
        server.expect(requestTo(KAKAO + "/v2/user/me")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess("""
                        {"id":9007199254740993,"kakao_account":{"email":"member@example.test",
                         "is_email_valid":true,"is_email_verified":true,
                         "profile":{"nickname":"Member","profile_image_url":"https://images.example.test/profile"}}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(verifier.verify("KAKAO", TOKEN)).isEqualTo(new SocialIdentityVerifier.Identity("KAKAO",
                "9007199254740993", "member@example.test", "Member", "https://images.example.test/profile"));
        server.verify();
    }

    @Test
    void rejectsKakaoTokensIssuedToAnotherAppWithoutFetchingUserInfo() {
        kakaoTokenInfo(74);

        assertFailure("KAKAO", TOKEN, false);
        server.verify();
    }

    @Test
    void rejectsKakaoUserInfoForADifferentSubject() {
        kakaoTokenInfo(73);
        server.expect(requestTo(KAKAO + "/v2/user/me"))
                .andRespond(withSuccess("""
                        {"id":9007199254740994}
                        """, MediaType.APPLICATION_JSON));

        assertFailure("KAKAO", TOKEN, false);
        server.verify();
    }

    @Test
    void classifiesKakaoTemporaryErrorAsUnavailableWithoutExposingTheToken() {
        server.expect(requestTo(KAKAO + "/v1/user/access_token_info"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":-1,\"msg\":\"" + TOKEN + "\"}"));

        assertFailure("KAKAO", TOKEN, true);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://accounts.google.com", "accounts.google.com"})
    void verifiesSignedGoogleIdentityForEitherOfficialIssuer(String issuer) throws JOSEException {
        googleKeys();
        String token = signedGoogleToken(key, issuer, GOOGLE_CLIENT, "stable-id", Instant.now().plusSeconds(180));

        assertThat(verifier.verify("GOOGLE", token)).isEqualTo(new SocialIdentityVerifier.Identity("GOOGLE",
                "stable-id", "member@example.test", "Member", "https://images.example.test/profile"));
        server.verify();
    }

    @Test
    void rejectsGoogleSignatureAndRequiredClaimFailures() throws JOSEException {
        googleKeys();
        String issuer = "https://accounts.google.com";
        Instant validUntil = Instant.now().plusSeconds(180);

        assertFailure("GOOGLE", signedGoogleToken(otherKey, issuer, GOOGLE_CLIENT, "stable-id", validUntil), false);
        assertFailure("GOOGLE", signedGoogleToken(key, issuer, "different-client", "stable-id", validUntil), false);
        assertFailure("GOOGLE", signedGoogleToken(key, "https://example.test", GOOGLE_CLIENT, "stable-id", validUntil), false);
        assertFailure("GOOGLE", signedGoogleToken(key, issuer, GOOGLE_CLIENT, "stable-id", Instant.now().minusSeconds(1)), false);
        assertFailure("GOOGLE", signedGoogleToken(key, issuer, GOOGLE_CLIENT, "stable-id", null), false);
        assertFailure("GOOGLE", signedGoogleToken(key, issuer, GOOGLE_CLIENT, null, validUntil), false);
        server.verify();
    }

    @Test
    void classifiesGoogleJwkOutageAsUnavailable() throws JOSEException {
        server.expect(requestTo(GOOGLE_JWKS)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        String token = signedGoogleToken(key, "https://accounts.google.com", GOOGLE_CLIENT, "stable-id", Instant.now().plusSeconds(180));

        assertFailure("GOOGLE", token, true);
        server.verify();
    }

    @Test
    void classifiesMalformedGoogleKeyDataAsUnavailable() throws JOSEException {
        server.expect(requestTo(GOOGLE_JWKS)).andRespond(withSuccess("{\"keys\":{}", MediaType.APPLICATION_JSON));
        String token = signedGoogleToken(key, "https://accounts.google.com", GOOGLE_CLIENT, "stable-id",
                Instant.now().plusSeconds(180));
        assertFailure("GOOGLE", token, true);
        server.verify();
    }

    private void assertFailure(String provider, String token, boolean unavailable) {
        assertThatThrownBy(() -> verifier.verify(provider, token))
                .isInstanceOfSatisfying(SocialIdentityVerifier.Failure.class, failure -> {
                    assertThat(failure.unavailable()).isEqualTo(unavailable);
                    assertThat(failure.getMessage()).doesNotContain(token);
                    assertThat(failure.getCause()).isNull();
                });
    }

    private void kakaoTokenInfo(long appId) {
        server.expect(requestTo(KAKAO + "/v1/user/access_token_info"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess("{\"id\":9007199254740993,\"app_id\":" + appId + ",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
    }

    private void googleKeys() {
        server.expect(requestTo(GOOGLE_JWKS)).andRespond(
                withSuccess(new JWKSet(key.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
    }

    private static String signedGoogleToken(RSAKey signingKey, String issuer, String audience,
                                            String subject, Instant expiry) throws JOSEException {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
                .claim("email_verified", true).claim("email", "member@example.test")
                .claim("name", "Member").claim("picture", "https://images.example.test/profile");
        if (expiry != null) {
            claims.expirationTime(Date.from(expiry));
        }
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }
}
