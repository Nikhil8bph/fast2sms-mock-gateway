package com.example.fast2smsmockgateway.controller;

import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.dtos.response.SendResponse;
import com.example.fast2smsmockgateway.security.ApiKeyFilter;
import com.example.fast2smsmockgateway.service.RcsService;
import com.example.fast2smsmockgateway.service.VoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/dev")
@SecurityRequirement(name = "fast2smsKey")
public class ChannelController {
    private final RcsService rcs;
    private final VoiceService voice;

    public ChannelController(RcsService rcs, VoiceService voice) {
        this.rcs = rcs;
        this.voice = voice;
    }

    @Operation(summary = "Send an RCS plain, rich, media or carousel message")
    @RequestMapping(value = "/rcs", method = {RequestMethod.GET, RequestMethod.POST})
    SendResponse rcs(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> query, @RequestBody(required = false) Map<String, Object> body,
            HttpMethod method) {
        return rcs.send(account, GatewayRequest.of(query, body), HttpMethod.POST.equals(method));
    }

    @Operation(summary = "Submit a simple, key-press or call patch voice campaign")
    @PostMapping("/voice/bulk")
    Map<String, Object> voice(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> query, @RequestBody(required = false) Map<String, Object> body) {
        return voice.send(account, GatewayRequest.of(query, body));
    }

    @Operation(summary = "List approved sandbox voice files")
    @GetMapping("/voice/files")
    Map<String, Object> voiceFiles(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account) {
        return voice.files(account);
    }

    @Operation(summary = "Get a voice campaign and optional per-number results")
    @GetMapping("/voice/report")
    Map<String, Object> voiceReport(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> query) {
        return voice.report(account, GatewayRequest.of(query, null));
    }
}
