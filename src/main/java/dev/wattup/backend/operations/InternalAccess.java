package dev.wattup.backend.operations;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
class InternalAccess implements HandlerInterceptor, WebMvcConfigurer {
    private final JsonMapper json;
    private final Set<InetAddress> allowed;
    private final byte[] read;
    private final byte[] write;

    InternalAccess(JsonMapper json, @Value("${wattup.internal.allowed-addresses:127.0.0.1,::1}") String addresses,
                   @Value("${wattup.internal.read-token:}") String read,
                   @Value("${wattup.internal.write-token:}") String write) {
        this.json = json;
        this.allowed = Arrays.stream(addresses.split(",")).map(String::strip).filter(ip -> !ip.isEmpty())
                .map(InetAddress::ofLiteral).collect(Collectors.toUnmodifiableSet());
        this.read = key(read);
        this.write = key(write);
        if (this.read.length != 0 && MessageDigest.isEqual(this.read, this.write)) {
            throw new IllegalArgumentException("Internal read and write tokens must be distinct");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/internal/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (read.length == 0 && write.length == 0) {
            return denied(response, 404, "NOT_FOUND");
        }
        if (!allowedAddress(request.getRemoteAddr())) {
            return denied(response, 403, "ACCESS_DENIED");
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || auth.length() > 135 || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return denied(response, 401, "AUTHENTICATION_REQUIRED");
        }
        byte[] supplied = auth.substring(7).getBytes(StandardCharsets.US_ASCII);
        boolean canRead = read.length != 0 && MessageDigest.isEqual(read, supplied);
        boolean canWrite = write.length != 0 && MessageDigest.isEqual(write, supplied);
        if (!canRead && !canWrite) {
            return denied(response, 401, "AUTHENTICATION_REQUIRED");
        }
        if (!canWrite && !Set.of("GET", "HEAD").contains(request.getMethod())) {
            return denied(response, 403, "ACCESS_DENIED");
        }
        response.setHeader("Cache-Control", "no-store");
        return true;
    }

    private boolean allowedAddress(String ip) {
        try {
            return allowed.contains(InetAddress.ofLiteral(ip));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean denied(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        json.writeValue(response.getOutputStream(), new InternalFailure(status, code).body());
        return false;
    }

    private static byte[] key(String key) {
        if (!key.isEmpty() && !key.matches("[A-Za-z0-9_-]{43,128}")) {
            throw new IllegalArgumentException("Invalid internal operation token format");
        }
        return key.getBytes(StandardCharsets.US_ASCII);
    }
}
