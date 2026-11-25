package com.example.fast2smsmockgateway.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "sandbox_otp_challenge")
public class OtpChallengeEntity implements Persistable<String> {
    @Transient
    private boolean newEntity;
    @Id
    public String id;
    public String code;
    @Column(columnDefinition = "TEXT")
    public String payload;
    public Instant issuedAt;
    public Instant lastSentAt;
    public Instant expiresAt;
    public int sends;
    public int failures;
    public boolean verified;

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public void markNew() {
        newEntity = true;
    }
}
