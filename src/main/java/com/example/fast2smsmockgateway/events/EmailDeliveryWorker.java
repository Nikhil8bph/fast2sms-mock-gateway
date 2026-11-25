package com.example.fast2smsmockgateway.events;

import com.example.fast2smsmockgateway.clients.SandboxMailClient;
import com.example.fast2smsmockgateway.dtos.common.ApiCall;
import com.example.fast2smsmockgateway.service.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EmailDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryWorker.class);
    private final OutboxService outbox;
    private final SandboxMailClient mail;

    public EmailDeliveryWorker(OutboxService outbox, SandboxMailClient mail) {
        this.outbox = outbox;
        this.mail = mail;
    }

    @Scheduled(fixedDelayString = "${sandbox.outbox-delay-ms:2000}",
            initialDelayString = "${sandbox.outbox-delay-ms:2000}")
    public void deliver() {
        for (String id : outbox.readyIds()) {
            outbox.claim(id).ifPresent(this::deliver);
        }
    }

    private void deliver(ApiCall call) {
        String failure = null;
        try {
            mail.send(call);
        } catch (MailException exception) {
            failure = exception.getClass().getSimpleName();
            log.warn("Sandbox SMTP attempt {} failed for {} ({})", call.attempts(), call.id(), failure);
        }
        outbox.finish(call, failure);
    }
}
