package com.example.fast2smsmockgateway.clients;

import com.example.fast2smsmockgateway.config.SandboxProperties;
import com.example.fast2smsmockgateway.exception.GatewayException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Optional;

@Component
public class DltExportClient {
    static final int MAX_BYTES = 2_000_000;
    private final URI origin;

    public DltExportClient(SandboxProperties config) {
        String configured = config.dltExcelOrigin();
        origin = configured == null || configured.isBlank() ? null : URI.create(configured);
        if (origin != null && (!https(origin) || origin.getRawQuery() != null
                || (origin.getRawPath() != null && !origin.getRawPath().isEmpty() && !"/".equals(origin.getRawPath())))) {
            throw new IllegalArgumentException("DLT_EXCEL_ORIGIN must be an HTTPS origin without a path or query");
        }
    }

    public Optional<byte[]> fetch(String source) {
        if (origin == null) {
            return Optional.empty();
        }
        URI uri = URI.create(source);
        if (!https(uri) || !origin.getHost().equalsIgnoreCase(uri.getHost()) || port(origin) != port(uri)) {
            throw GatewayException.badRequest(400, "Excel URL must match the configured DLT_EXCEL_ORIGIN");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_BYTES) {
                throw GatewayException.badRequest(400, "Excel URL must return a file of at most 2 MB");
            }
            try (var stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                    throw GatewayException.badRequest(400, "Excel URL must return a file of at most 2 MB");
                }
                return Optional.of(bytes);
            }
        } catch (IOException exception) {
            throw GatewayException.badRequest(400, "Unable to read the DLT export from its configured origin");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean https(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
    }

    private static int port(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }
}
