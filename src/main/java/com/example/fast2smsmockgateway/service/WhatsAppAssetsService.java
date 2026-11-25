package com.example.fast2smsmockgateway.service;

import com.example.fast2smsmockgateway.dtos.common.SandboxResource;
import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.dtos.request.MediaUploadRequest;
import com.example.fast2smsmockgateway.dtos.response.QrImage;
import com.example.fast2smsmockgateway.util.QrImages;
import com.example.fast2smsmockgateway.exception.GatewayException;
import com.example.fast2smsmockgateway.validation.Fields;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

@Service
@Transactional
public class WhatsAppAssetsService {
    private final ResourceStore store;
    private final CallRecorder recorder;

    public WhatsAppAssetsService(ResourceStore store, CallRecorder recorder) {
        this.store = store;
        this.recorder = recorder;
    }

    public Map<String, Object> upload(String account, String phone, MediaUploadRequest request, boolean handle) {
        validatePhoneId(phone);
        if (!"whatsapp".equals(request.product()) || request.mimeType().isBlank() || request.content().length == 0) {
            throw GatewayException.badRequest(400, "Provide a file, type and messaging_product=whatsapp");
        }
        String id = store.numericId();
        Map<String, Object> metadata = Map.of("id", id, "mime_type", request.mimeType(),
                "file_size", request.content().length, "sha256", sha256(request.content()),
                "messaging_product", "whatsapp");
        store.save(account, "wa-media", phone, id, metadata, request.content());
        recorder.audit(account, handle ? "WhatsApp media handle" : "WhatsApp media upload", metadata);
        return handle ? Map.of("h", "2:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.getBytes(StandardCharsets.UTF_8))) : Map.of("id", id);
    }

    public Map<String, Object> media(String account, String phone, String id, String localUrl) {
        var resource = mediaResource(account, phone, id);
        var data = store.data(resource);
        data.put("url", localUrl);
        recorder.audit(account, "WhatsApp media lookup", Map.of("id", id, "phone_number_id", phone));
        return data;
    }

    public byte[] download(String account, String phone, String id) {
        var resource = mediaResource(account, phone, id);
        recorder.audit(account, "WhatsApp media download", Map.of("id", id, "phone_number_id", phone));
        return resource.content();
    }

    public Map<String, Object> qrCodes(String account, String phone, String id) {
        var codes = id == null ? store.list(account, "wa-qr", phone).stream().map(store::data).toList()
                : List.of(store.data(findQr(account, phone, id)));
        recorder.audit(account, "WhatsApp QR lookup", Map.of("phone_number_id", phone));
        return Map.of("data", codes.stream().map(this::qrResponse).toList());
    }

    public QrImage qrImage(String account, String phone, String id, String format) {
        Map<String, Object> data = store.data(findQr(account, phone, id));
        if (!format.equals(data.get("generate_qr_image"))) {
            throw GatewayException.missing("WhatsApp QR image not found");
        }
        recorder.audit(account, "WhatsApp QR image", Map.of("code", id));
        return QrImages.render((String) data.get("deep_link_url"), format);
    }

    public Map<String, Object> saveQr(String account, String phone, String id, GatewayRequest request, String base) {
        validatePhoneId(phone);
        Fields fields = new Fields(request.fields());
        String message = fields.require("prefilled_message");
        String format = fields.get("generate_qr_image");
        Map<String, Object> data = new LinkedHashMap<>();
        if (id == null) {
            id = store.numericId();
            fields.require("generate_qr_image");
        } else {
            data.putAll(store.data(requireQr(account, phone, id)));
            if (format.isBlank()) {
                format = (String) data.getOrDefault("generate_qr_image", "PNG");
            }
        }
        if (!Set.of("PNG", "SVG").contains(format)) {
            throw GatewayException.badRequest(400, "generate_qr_image must be PNG or SVG");
        }
        String link = base + "/" + id;
        data.put("code", id);
        data.put("prefilled_message", message);
        data.put("deep_link_url", link);
        data.put("qr_image_url", link + "/image." + format.toLowerCase(java.util.Locale.ROOT));
        data.put("generate_qr_image", format);
        store.save(account, "wa-qr", phone, id, data);
        recorder.audit(account, "WhatsApp QR save", data);
        return qrResponse(data);
    }

    public Map<String, Object> deleteQr(String account, String phone, String id) {
        store.delete(requireQr(account, phone, id));
        recorder.audit(account, "WhatsApp QR delete", Map.of("code", id));
        return Map.of("success", true);
    }

    public Map<String, Object> blocked(String account, String phone) {
        var users = store.list(account, "wa-block", phone).stream().map(store::data).toList();
        recorder.audit(account, "WhatsApp blocked users lookup", Map.of("phone_number_id", phone));
        return Map.of("data", List.of(Map.of("block_users", users)));
    }

    public Map<String, Object> changeBlocked(String account, String phone, GatewayRequest request, boolean add) {
        validatePhoneId(phone);
        Fields fields = new Fields(request.fields());
        if (!"whatsapp".equals(fields.require("messaging_product"))) {
            throw GatewayException.badRequest(400, "messaging_product must be whatsapp");
        }
        List<Map<String, Object>> users = new ArrayList<>();
        for (Fields user : fields.objects("block_users")) {
            String input = user.require("user");
            String id = user.internationalMobile(input);
            Map<String, Object> data = Map.of("input", input, "wa_id", id);
            if (add) {
                store.save(account, "wa-block", phone, id, data);
            } else {
                store.lock(account, "wa-block", phone, id).ifPresent(store::delete);
            }
            users.add(data);
        }
        recorder.audit(account, add ? "WhatsApp block users" : "WhatsApp unblock users", request.fields());
        return Map.of("messaging_product", "whatsapp",
                "block_users", Map.of(add ? "added_users" : "removed_users", users));
    }

    private SandboxResource mediaResource(String account, String phone, String id) {
        return store.find(account, "wa-media", phone, id)
                .orElseThrow(() -> GatewayException.missing("WhatsApp media not found"));
    }

    private SandboxResource findQr(String account, String phone, String id) {
        return store.find(account, "wa-qr", phone, id)
                .orElseThrow(() -> GatewayException.missing("WhatsApp QR code not found"));
    }

    private Map<String, Object> qrResponse(Map<String, Object> data) {
        Map<String, Object> response = new LinkedHashMap<>(data);
        response.remove("generate_qr_image");
        return response;
    }

    private SandboxResource requireQr(String account, String phone, String id) {
        return store.lock(account, "wa-qr", phone, id)
                .orElseThrow(() -> GatewayException.missing("WhatsApp QR code not found"));
    }

    private void validatePhoneId(String phone) {
        if (!phone.matches("[0-9]{1,32}")) {
            throw GatewayException.badRequest(400, "Invalid phone_number_id");
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
