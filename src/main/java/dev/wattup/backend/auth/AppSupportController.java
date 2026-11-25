package dev.wattup.backend.auth;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;

@RestController
class AppSupportController {
    private static final int MAX_REPORT_BYTES = 16_384;
    private final AppSupport support;
    private final AuthRateLimit rate;
    private final ObjectReader reportJson;

    AppSupportController(AppSupport support, AuthRateLimit rate, JsonMapper json) {
        this.support = support;
        this.rate = rate;
        this.reportJson = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @GetMapping("/api/app/notices")
    AppSupport.Notices notices(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return support.notices(page, size);
    }

    @PostMapping(path = "/api/support/reports", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ReportRequest.class)))
    ResponseEntity<AppSupport.Received> report(@AuthenticationPrincipal Jwt access, HttpServletRequest request) {
        rate.hit("support-report", access.getSubject(), 5, Duration.ofHours(1), "RATE_LIMITED");
        if (request.getContentLengthLong() > MAX_REPORT_BYTES) {
            throw new AuthFailure(413, "REPORT_TOO_LARGE");
        }
        JsonNode input;
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_REPORT_BYTES + 1);
            if (bytes.length > MAX_REPORT_BYTES) {
                throw new AuthFailure(413, "REPORT_TOO_LARGE");
            }
            input = reportJson.readTree(bytes);
        } catch (IOException | JacksonException e) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        return ResponseEntity.status(201).body(support.report(access, input));
    }

    record ReportRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"STATION_INFORMATION", "APP_ERROR", "OTHER"}) String category,
            String stationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 2000) String message,
            @Schema(format = "email", maxLength = 254) String contactEmail) {}
}
