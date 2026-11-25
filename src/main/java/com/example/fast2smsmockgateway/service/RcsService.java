package com.example.fast2smsmockgateway.service;

import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.dtos.response.SendResponse;
import com.example.fast2smsmockgateway.exception.GatewayException;
import com.example.fast2smsmockgateway.validation.Fields;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@Service
public class RcsService {
    private final CallRecorder recorder;

    public RcsService(CallRecorder recorder) {
        this.recorder = recorder;
    }

    @Transactional
    public SendResponse send(String account, GatewayRequest request, boolean post) {
        Fields fields = new Fields(request.fields());
        fields.require("bot_id");
        String mobile = fields.indianMobile(fields.require("to"));
        String type = fields.require("type");
        switch (type) {
            case "plain" -> text(fields, "message", 3000, true);
            case "rich" -> {
                text(fields, "title", 100, false);
                text(fields, "message", 3000, true);
                url(fields, "media_url", false);
            }
            case "media" -> url(fields, "media_url", true);
            case "carousel" -> carousel(fields, post);
            default -> throw GatewayException.badRequest(400, "Invalid RCS message type");
        }
        if ("plain".equals(type) || "rich".equals(type)) {
            suggestion(fields);
        }
        String id = recorder.enqueue(account, "RCS", "RCS " + type, request.fields(),
                List.of(mobile), Instant.now()).requestId();
        return new SendResponse(true, id, List.of("RCS Message will be sent."), null);
    }

    private void carousel(Fields fields, boolean post) {
        if (!post) {
            throw GatewayException.badRequest(400, "RCS carousel requires a POST JSON request");
        }
        List<Fields> cards = fields.objects("cards");
        if (cards.size() < 2 || cards.size() > 10) {
            throw GatewayException.badRequest(400, "RCS carousel requires 2 to 10 cards");
        }
        for (Fields card : cards) {
            text(card, "title", 255, true);
            text(card, "description", 2000, true);
            url(card, "media_url", false);
            suggestion(card);
        }
    }

    private void suggestion(Fields fields) {
        String type = fields.get("suggestion_type");
        if (type.isBlank()) {
            return;
        }
        text(fields, "suggestion_text", 25, true);
        switch (type) {
            case "openUrl" -> url(fields, "suggestion_url", true);
            case "dial" -> fields.internationalMobile(fields.require("suggestion_phone"));
            case "viewLocation" -> {
                coordinate(fields, "latitude", 90);
                coordinate(fields, "longitude", 180);
            }
            default -> throw GatewayException.badRequest(400, "Invalid RCS suggestion_type");
        }
    }

    private void text(Fields fields, String name, int limit, boolean required) {
        String value = required ? fields.require(name) : fields.get(name);
        if (value.codePointCount(0, value.length()) > limit) {
            throw GatewayException.badRequest(400, name + " must be at most " + limit + " characters");
        }
    }

    private void url(Fields fields, String name, boolean required) {
        String value = required ? fields.require(name) : fields.get(name);
        if (value.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // The same error covers malformed and unsupported URLs.
        }
        throw GatewayException.badRequest(400, name + " must be an HTTP or HTTPS URL");
    }

    private void coordinate(Fields fields, String name, int limit) {
        try {
            double value = Double.parseDouble(fields.require(name));
            if (Math.abs(value) <= limit) {
                return;
            }
        } catch (NumberFormatException ignored) {
            // The same error covers malformed and out-of-range coordinates.
        }
        throw GatewayException.badRequest(400, "Invalid " + name);
    }
}
