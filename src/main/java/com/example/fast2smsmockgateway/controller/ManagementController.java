package com.example.fast2smsmockgateway.controller;

import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.security.ApiKeyFilter;
import com.example.fast2smsmockgateway.service.ManagementService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
@RequestMapping("/dev")
@SecurityRequirement(name = "fast2smsKey")
public class ManagementController {
    private static final String VERSIONED = "/whatsapp/{version:v[0-9]+\\.[0-9]+}";
    private static final String WEBHOOK = "/webhook/v2/{service:sms|otp|whatsapp|rcs|voice}";
    private static final String DLT_IMPORT = "/dlt_manager/add_{kind:sender|template}_excel";
    private final ManagementService management;

    public ManagementController(ManagementService management) {
        this.management = management;
    }

    @GetMapping("/dlt_manager")
    Map<String, Object> dltList(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> query) {
        return management.dltList(account, GatewayRequest.of(query, null));
    }

    @PostMapping("/dlt_manager/add_{kind:entity|sender|template}")
    Map<String, Object> dltAdd(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String kind, @RequestParam Map<String, String> query,
            @RequestBody(required = false) Map<String, Object> body) {
        return management.dltAdd(account, kind, GatewayRequest.of(query, body));
    }

    @PostMapping(DLT_IMPORT)
    Map<String, Object> dltImport(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String kind, @RequestParam Map<String, String> query,
            @RequestBody(required = false) Map<String, Object> body) {
        return management.dltImport(account, kind, GatewayRequest.of(query, body), null);
    }

    @PostMapping(value = DLT_IMPORT, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> dltUpload(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String kind, @RequestParam Map<String, String> query,
            @RequestParam MultipartFile file) throws IOException {
        return management.dltImport(account, kind, GatewayRequest.of(query, null), file.getBytes());
    }

    @GetMapping("/dlt_manager/whatsapp")
    Map<String, Object> wabaDetails(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> query) {
        return management.wabaDetails(account, GatewayRequest.of(query, null));
    }

    @GetMapping(VERSIONED + "/{wabaId}/phone_numbers")
    Map<String, Object> phoneNumbers(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String wabaId, @RequestParam Map<String, String> query) {
        return management.phoneNumbers(account, wabaId, GatewayRequest.of(query, null));
    }

    @GetMapping(VERSIONED + "/{id}")
    Map<String, Object> whatsappObject(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String id, @RequestParam Map<String, String> query) {
        return management.object(account, id, GatewayRequest.of(query, null));
    }

    @GetMapping(VERSIONED + "/{phoneId}/whatsapp_business_profile")
    Map<String, Object> getProfile(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String version, @PathVariable String phoneId, @RequestParam Map<String, String> query) {
        String mediaBase = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/dev/whatsapp/" + version + "/" + phoneId + "/media/").build().toUriString();
        return management.profile(account, phoneId, GatewayRequest.of(query, null), false, mediaBase);
    }

    @PostMapping(VERSIONED + "/{phoneId}/whatsapp_business_profile")
    Map<String, Object> updateProfile(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String phoneId, @RequestBody Map<String, Object> body) {
        return management.profile(account, phoneId, new GatewayRequest(body), true, "");
    }

    @GetMapping(VERSIONED + "/{wabaId}/message_templates")
    Map<String, Object> listTemplates(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String wabaId, @RequestParam Map<String, String> query) {
        return management.listTemplates(account, wabaId, GatewayRequest.of(query, null));
    }

    @PostMapping(VERSIONED + "/{wabaId}/message_templates")
    Map<String, Object> createTemplate(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String wabaId, @RequestBody Map<String, Object> body) {
        return management.createTemplate(account, wabaId, new GatewayRequest(body));
    }

    @PostMapping(VERSIONED + "/{id}")
    Map<String, Object> editTemplate(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        return management.editTemplate(account, id, new GatewayRequest(body));
    }

    @DeleteMapping(VERSIONED + "/{wabaId}/message_templates")
    Map<String, Object> deleteTemplate(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String wabaId, @RequestParam Map<String, String> query) {
        return management.deleteTemplate(account, wabaId, GatewayRequest.of(query, null));
    }

    @GetMapping(WEBHOOK)
    Map<String, Object> webhooks(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String service) {
        return management.webhooks(account, service);
    }

    @PostMapping(WEBHOOK)
    Map<String, Object> createWebhook(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String service,
            @RequestParam Map<String, String> query, @RequestBody(required = false) Map<String, Object> body) {
        return management.saveWebhook(account, service, null, GatewayRequest.of(query, body));
    }

    @PutMapping(WEBHOOK + "/{id}")
    Map<String, Object> updateWebhook(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String service,
            @PathVariable String id, @RequestParam Map<String, String> query,
            @RequestBody(required = false) Map<String, Object> body) {
        return management.saveWebhook(account, service, id, GatewayRequest.of(query, body));
    }

    @DeleteMapping(WEBHOOK + "/{id}")
    Map<String, Object> deleteWebhook(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String service,
            @PathVariable String id) {
        return management.deleteWebhook(account, service, id);
    }
}
