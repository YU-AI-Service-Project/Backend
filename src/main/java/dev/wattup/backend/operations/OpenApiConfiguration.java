package dev.wattup.backend.operations;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class OpenApiConfiguration {
    private static final Map<String, String> POST_STATUS = Map.ofEntries(
            Map.entry("/api/auth/social/signup", "201"),
            Map.entry("/api/auth/signup/email", "201"),
            Map.entry("/api/users/me/favorites", "201"),
            Map.entry("/api/users/me/social-accounts/{provider}", "201"),
            Map.entry("/api/support/reports", "201"),
            Map.entry("/api/auth/email/verifications", "202"),
            Map.entry("/api/auth/password-resets", "202"),
            Map.entry("/internal/collector/runs", "202"),
            Map.entry("/api/auth/logout", "204"),
            Map.entry("/api/auth/logout-all", "204"),
            Map.entry("/api/auth/password-resets/confirm", "204"));
    private static final Set<String> ACCOUNT_AUTH = Set.of("/api/auth/logout", "/api/auth/logout-all",
            "/api/support/reports");

    @Bean
    OpenApiCustomizer wattupContract() {
        return api -> {
            api.info(new Info().title("WattUp Backend").version("0.1.0"));
            security(api, "accessToken", "WattUp access token", "JWT");
            security(api, "internalReadToken", "Internal allowlisted network and read or write token", null);
            security(api, "internalWriteToken", "Internal allowlisted network and write token", null);
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                if (path.startsWith("/api/users/me") || ACCOUNT_AUTH.contains(path)) {
                    operation.addSecurityItem(new SecurityRequirement().addList("accessToken"));
                } else if (path.startsWith("/internal/")) {
                    operation.addSecurityItem(new SecurityRequirement().addList("internalWriteToken"));
                    if (method.name().equals("GET")) {
                        operation.addSecurityItem(new SecurityRequirement().addList("internalReadToken"));
                    }
                }
                String status = switch (method) {
                    case POST -> POST_STATUS.get(path);
                    case DELETE -> "204";
                    case PATCH -> "/api/users/me/password".equals(path) ? "204" : null;
                    default -> null;
                };
                if (status != null) {
                    successStatus(operation, status);
                }
            }));
        };
    }

    private static void security(OpenAPI api, String name, String description, String format) {
        api.getComponents().addSecuritySchemes(name, new SecurityScheme().type(SecurityScheme.Type.HTTP)
                .scheme("bearer").bearerFormat(format).description(description));
    }

    private static void successStatus(Operation operation, String status) {
        var inferred = operation.getResponses().remove("200");
        if (inferred == null || operation.getResponses().containsKey(status)) {
            return;
        }
        inferred.setDescription(switch (status) {
            case "201" -> "Created";
            case "202" -> "Accepted";
            default -> "No Content";
        });
        if ("204".equals(status)) {
            inferred.setContent(null);
        }
        operation.getResponses().addApiResponse(status, inferred);
    }
}
