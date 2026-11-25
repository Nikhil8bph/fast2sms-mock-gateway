package com.example.fast2smsmockgateway.controller;

import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.security.ApiKeyFilter;
import com.example.fast2smsmockgateway.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/dev")
@SecurityRequirement(name = "fast2smsKey")
public class ReportController {
    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @Operation(summary = "Get the sandbox wallet balance")
    @PostMapping("/wallet")
    Map<String, Object> wallet(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account) {
        return reports.wallet(account);
    }

    @Operation(summary = "Get an SMS delivery report")
    @GetMapping("/dlr/{requestId}")
    Map<String, Object> smsDlr(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String requestId) {
        return reports.delivery(account, "SMS", requestId);
    }

    @Operation(summary = "Get a WhatsApp delivery report")
    @GetMapping("/whatsapp/{requestId}")
    Map<String, Object> whatsappDlr(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String requestId) {
        return reports.delivery(account, "WHATSAPP", requestId);
    }

    @Operation(summary = "Get SMS or WhatsApp logs or a summary")
    @GetMapping("/{channel:sms|whatsapp}_{view:logs|summary}")
    Map<String, Object> analytics(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account, @PathVariable String channel,
            @PathVariable String view, @RequestParam Map<String, String> parameters) {
        return reports.analytics(account, channel.toUpperCase(java.util.Locale.ROOT), "summary".equals(view),
                GatewayRequest.of(parameters, null));
    }
}
