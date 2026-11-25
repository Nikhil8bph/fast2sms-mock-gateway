package com.example.fast2smsmockgateway.controller;

import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.dtos.request.MediaUploadRequest;
import com.example.fast2smsmockgateway.security.ApiKeyFilter;
import com.example.fast2smsmockgateway.service.WhatsAppAssetsService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/dev/whatsapp/{version:v[0-9]+\\.[0-9]+}/{phoneId}")
@SecurityRequirement(name = "fast2smsKey")
public class WhatsAppAssetsController {
    private final WhatsAppAssetsService assets;

    public WhatsAppAssetsController(WhatsAppAssetsService assets) {
        this.assets = assets;
    }

    @PostMapping(value = "/{upload:media|media_handle}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> upload(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String phoneId,
            @PathVariable String upload, @RequestParam MultipartFile file,
            @RequestParam(name = "messaging_product") String product, @RequestParam String type) throws IOException {
        return assets.upload(account, phoneId, new MediaUploadRequest(product, type, file.getBytes()),
                "media_handle".equals(upload));
    }

    @GetMapping("/media/{id}")
    Map<String, Object> media(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String version,
            @PathVariable String phoneId, @PathVariable String id) {
        return assets.media(account, phoneId, id,
                absolutePath("/dev/whatsapp/" + version + "/" + phoneId + "/media/" + id + "/file"));
    }

    @GetMapping(value = "/media/{id}/file", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<byte[]> download(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String phoneId,
            @PathVariable String id) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment")
                .header("X-Content-Type-Options", "nosniff").body(assets.download(account, phoneId, id));
    }

    @GetMapping({"/message_qrdls", "/message_qrdls/{id}"})
    Map<String, Object> qrCodes(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String phoneId,
            @PathVariable(required = false) String id) {
        return assets.qrCodes(account, phoneId, id);
    }

    @PostMapping({"/message_qrdls", "/message_qrdls/{id}"})
    Map<String, Object> saveQr(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String version,
            @PathVariable String phoneId, @PathVariable(required = false) String id,
            @RequestBody Map<String, Object> body) {
        return assets.saveQr(account, phoneId, id, new GatewayRequest(body),
                absolutePath("/dev/whatsapp/" + version + "/" + phoneId + "/message_qrdls"));
    }

    @GetMapping("/message_qrdls/{id}/image.{format:png|svg}")
    ResponseEntity<byte[]> qrImage(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String phoneId,
            @PathVariable String id, @PathVariable String format) {
        var image = assets.qrImage(account, phoneId, id, format.toUpperCase(java.util.Locale.ROOT));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mediaType()))
                .header("X-Content-Type-Options", "nosniff").body(image.content());
    }

    @DeleteMapping("/message_qrdls/{id}")
    Map<String, Object> deleteQr(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String phoneId,
            @PathVariable String id) {
        return assets.deleteQr(account, phoneId, id);
    }

    @GetMapping("/block_users")
    Map<String, Object> blocked(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String phoneId) {
        return assets.blocked(account, phoneId);
    }

    @PostMapping("/block_users")
    Map<String, Object> block(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String phoneId,
            @RequestBody Map<String, Object> body) {
        return assets.changeBlocked(account, phoneId, new GatewayRequest(body), true);
    }

    @DeleteMapping("/block_users")
    Map<String, Object> unblock(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String phoneId,
            @RequestBody Map<String, Object> body) {
        return assets.changeBlocked(account, phoneId, new GatewayRequest(body), false);
    }

    private String absolutePath(String path) {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(path).build().toUriString();
    }
}
