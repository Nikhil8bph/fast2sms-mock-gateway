package com.example.fast2smsmockgateway.clients;

import com.example.fast2smsmockgateway.dtos.common.ApiCall;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SandboxMailClient {
    private final JavaMailSender sender;

    public SandboxMailClient(JavaMailSender sender) {
        this.sender = sender;
    }

    public void send(ApiCall call) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom(call.emailFrom());
        email.setTo(call.emailTo());
        email.setSubject("[Fast2SMS Sandbox] " + call.operation() + " " + call.requestId());
        email.setText("""
                Request ID: %s
                Operation: %s
                Phone recipients: %s

                %s
                """.formatted(call.requestId(), call.operation(), call.recipients(), call.payload()));
        sender.send(email);
    }
}
