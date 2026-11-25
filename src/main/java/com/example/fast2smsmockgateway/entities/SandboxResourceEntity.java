package com.example.fast2smsmockgateway.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "sandbox_resource")
public class SandboxResourceEntity {
    @Id
    @Column(name = "resource_key")
    public String key;
    public String account;
    public String kind;
    @Column(name = "resource_scope")
    public String scope;
    @Column(name = "external_id")
    public String reference;
    @Column(columnDefinition = "TEXT")
    public String payload;
    @Lob
    @Column(columnDefinition = "LONGBLOB")
    public byte[] content;
}
