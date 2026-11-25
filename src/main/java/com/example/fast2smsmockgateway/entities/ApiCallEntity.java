package com.example.fast2smsmockgateway.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "sandbox_api_call")
public class ApiCallEntity implements Persistable<String> {
    @Transient
    private boolean newEntity;
    @Id
    public String id;
    public String requestId;
    public String account;
    public String channel;
    public String operation;
    @Column(columnDefinition = "LONGTEXT")
    public String payload;
    @Column(columnDefinition = "LONGTEXT")
    public String recipients;
    public String emailFrom;
    public String emailTo;
    public Instant createdAt;
    public Instant dueAt;
    public String status;
    public int attempts;
    public String lease;
    public Instant sentAt;
    public String failure;

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
