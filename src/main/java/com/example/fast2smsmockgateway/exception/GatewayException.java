package com.example.fast2smsmockgateway.exception;

import org.springframework.http.HttpStatus;

public class GatewayException extends RuntimeException {
    private final HttpStatus status;
    private final int code;
    private final String field;

    public GatewayException(HttpStatus status, int code, String message) {
        this(status, code, message, null);
    }

    private GatewayException(HttpStatus status, int code, String message, String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public HttpStatus status() {
        return status;
    }

    public int code() {
        return code;
    }

    public String field() {
        return field;
    }

    public static GatewayException badField(String field, int code, String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, code, message, field);
    }

    public static GatewayException badRequest(int code, String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static GatewayException missing(String message) {
        return new GatewayException(HttpStatus.NOT_FOUND, 404, message);
    }
}
