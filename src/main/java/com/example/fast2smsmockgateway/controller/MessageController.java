package com.example.fast2smsmockgateway.controller;

import com.example.fast2smsmockgateway.dtos.request.GatewayRequest;
import com.example.fast2smsmockgateway.dtos.response.SendResponse;
import com.example.fast2smsmockgateway.security.ApiKeyFilter;
import com.example.fast2smsmockgateway.service.MessageService;
import com.example.fast2smsmockgateway.service.OtpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PathVariable;
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
public class MessageController {
    private final MessageService messages;
    private final OtpService otps;

    public MessageController(MessageService messages, OtpService otps) {
        this.messages = messages;
        this.otps = otps;
    }

    @Operation(summary = "Send Quick, DLT, DLT Manual or OTP SMS")
    @RequestMapping(value = "/bulkV2", method = {RequestMethod.GET, RequestMethod.POST})
    SendResponse sms(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> parameters, @RequestBody(required = false) Map<String, Object> body) {
        return messages.sms(account, GatewayRequest.of(parameters, body), false);
    }

    @Operation(summary = "Send a batch of DLT or DLT Manual SMS")
    @PostMapping("/custom")
    SendResponse batchSms(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> parameters, @RequestBody(required = false) Map<String, Object> body) {
        return messages.sms(account, GatewayRequest.of(parameters, body), true);
    }

    @Operation(summary = "Send a WhatsApp template by Fast2SMS message ID")
    @RequestMapping(value = "/whatsapp", method = {RequestMethod.GET, RequestMethod.POST})
    Object whatsapp(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> parameters, @RequestBody(required = false) Map<String, Object> body) {
        return messages.whatsapp(account, GatewayRequest.of(parameters, body), "WhatsApp template", null, null);
    }

    @Operation(summary = "Send a WhatsApp session message")
    @PostMapping("/whatsapp-session")
    Object whatsappSession(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> parameters, @RequestBody(required = false) Map<String, Object> body) {
        return messages.whatsapp(account, GatewayRequest.of(parameters, body), "WhatsApp session", null, null);
    }

    @Operation(summary = "Send a WhatsApp Direct Send utility message")
    @PostMapping("/whatsapp-direct-send")
    Object whatsappDirect(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> parameters, @RequestBody(required = false) Map<String, Object> body) {
        return messages.whatsapp(account, GatewayRequest.of(parameters, body), "WhatsApp Direct Send", null, null);
    }

    @Operation(summary = "Send a WhatsApp message using the Meta request format")
    @PostMapping("/whatsapp/{version}/{phoneNumberId}/messages")
    Object whatsappMeta(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @PathVariable String version, @PathVariable String phoneNumberId, @RequestBody Map<String, Object> body) {
        return messages.whatsapp(account, new GatewayRequest(body), "WhatsApp Meta", version, phoneNumberId);
    }

    @Operation(summary = "Send a Smart OTP")
    @RequestMapping(value = "/otp/send", method = {RequestMethod.GET, RequestMethod.POST})
    Map<String, Object> sendOtp(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> parameters, @RequestBody(required = false) Map<String, Object> body) {
        return otps.send(account, GatewayRequest.of(parameters, body));
    }

    @Operation(summary = "Verify a Smart OTP")
    @RequestMapping(value = "/otp/verify", method = {RequestMethod.GET, RequestMethod.POST})
    Map<String, Object> verifyOtp(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> parameters, @RequestBody(required = false) Map<String, Object> body) {
        return otps.verify(account, GatewayRequest.of(parameters, body));
    }

    @Operation(summary = "Resend a Smart OTP")
    @RequestMapping(value = "/otp/resend", method = {RequestMethod.GET, RequestMethod.POST})
    Map<String, Object> resendOtp(@RequestAttribute(ApiKeyFilter.ACCOUNT) String account,
            @RequestParam Map<String, String> parameters, @RequestBody(required = false) Map<String, Object> body) {
        return otps.resend(account, GatewayRequest.of(parameters, body));
    }
}
