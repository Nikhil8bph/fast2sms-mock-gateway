package com.example.fast2smsmockgateway.validation;

import com.example.fast2smsmockgateway.exception.GatewayException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public record Fields(Map<?, ?> values) {
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter SCHEDULE =
            DateTimeFormatter.ofPattern("dd-MM-uuuu-HH-mm").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern INDIAN_MOBILE = Pattern.compile("[6-9][0-9]{9}");
    private static final Pattern E164 = Pattern.compile("[1-9][0-9]{6,14}");

    public String get(String field) {
        return switch (values.get(field)) {
            case String value -> value;
            case Number value -> value.toString();
            case null, default -> "";
        };
    }

    public String require(String field) {
        return require(field, 400, field + " is required");
    }

    public String require(String field, int code, String message) {
        String value = get(field);
        if (value.isBlank()) {
            throw GatewayException.badField(field, code, message);
        }
        return value;
    }

    public List<String> numbers(String field) {
        String numbers = require(field, 405, "Numbers Missing");
        return Arrays.stream(numbers.split(",", -1)).map(this::indianMobile).distinct().toList();
    }

    public NumberSelection smsNumbers() {
        String numbers = require("numbers", 405, "Numbers Missing");
        var accepted = new LinkedHashSet<String>();
        var rejected = new ArrayList<String>();
        for (String number : numbers.split(",", -1)) {
            String mobile = normalizeIndianMobile(number);
            if (INDIAN_MOBILE.matcher(mobile).matches()) {
                accepted.add(mobile);
            } else {
                rejected.add(number.trim());
            }
        }
        if (accepted.isEmpty()) {
            throw GatewayException.badRequest(411, "Invalid Numbers");
        }
        return new NumberSelection(List.copyOf(accepted), List.copyOf(rejected));
    }

    public record NumberSelection(List<String> accepted, List<String> rejected) {
    }

    public String indianMobile(String value) {
        String mobile = normalizeIndianMobile(value);
        if (!INDIAN_MOBILE.matcher(mobile).matches()) {
            throw GatewayException.badRequest(411, "Invalid Numbers");
        }
        return mobile;
    }

    private String normalizeIndianMobile(String value) {
        String mobile = value.trim();
        if (mobile.startsWith("+91") && mobile.length() == 13) {
            mobile = mobile.substring(3);
        } else if (mobile.startsWith("91") && mobile.length() == 12) {
            mobile = mobile.substring(2);
        }
        return mobile;
    }

    public String internationalMobile(String value) {
        String mobile = value.trim().replaceFirst("^\\+", "");
        if (!E164.matcher(mobile).matches()) {
            throw GatewayException.badRequest(411, "Invalid Numbers");
        }
        return mobile;
    }

    public Fields object(String field) {
        if (values.get(field) instanceof Map<?, ?> object) {
            return new Fields(object);
        }
        throw GatewayException.badRequest(400, field + " must be an object");
    }

    public List<Fields> objects(String field) {
        if (values.get(field) instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(Map.class::isInstance)) {
            return list.stream().map(item -> new Fields((Map<?, ?>) item)).toList();
        }
        throw GatewayException.badRequest(400, field + " must be a non-empty array of objects");
    }

    public int integer(String field, int fallback, int min, int max) {
        String value = get(field);
        if (value.isBlank() && !values.containsKey(field)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // The same client error applies to invalid and out-of-range values.
        }
        throw GatewayException.badRequest(400, field + " must be between " + min + " and " + max);
    }

    public Instant schedule(Instant now) {
        String value = get("schedule_time");
        if (value.isBlank() || "null".equalsIgnoreCase(value)) {
            return now;
        }
        try {
            Instant scheduled = value.matches("[0-9]+")
                    ? Instant.ofEpochSecond(Long.parseLong(value))
                    : LocalDateTime.parse(value, SCHEDULE).atZone(IST).toInstant();
            if (scheduled.isAfter(now) && scheduled.isBefore(Instant.parse("2038-01-19T03:14:07Z"))) {
                return scheduled;
            }
        } catch (DateTimeException | NumberFormatException ignored) {
            // Use the same response for all invalid schedules.
        }
        throw GatewayException.badRequest(400, "Invalid schedule_time");
    }
}
