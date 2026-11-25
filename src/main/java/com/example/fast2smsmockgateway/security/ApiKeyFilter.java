package com.example.fast2smsmockgateway.security;

import com.example.fast2smsmockgateway.config.SandboxProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiKeyFilter extends OncePerRequestFilter {
    public static final String ACCOUNT = "sandboxAccount";
    private final List<byte[]> keys;

    public ApiKeyFilter(SandboxProperties properties) {
        keys = Arrays.stream(properties.apiKeys().split(",")).map(String::trim)
                .filter(key -> !key.isEmpty()).map(ApiKeyFilter::digest).toList();
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("FAST2SMS_API_KEYS must contain a sandbox API key");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        return !path.equals("/dev") && !path.startsWith("/dev/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        String key = request.getHeader("Authorization");
        byte[] candidate = key == null ? new byte[0] : digest(key);
        boolean authorized = false;
        for (byte[] expected : keys) {
            authorized |= MessageDigest.isEqual(candidate, expected);
        }
        if (!authorized) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"return":false,"status_code":412,"message":"Invalid Authentication, Check Authorization Key"}
                    """);
            return;
        }
        request.setAttribute(ACCOUNT, HexFormat.of().formatHex(candidate));
        chain.doFilter(request, response);
    }

    private static byte[] digest(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
