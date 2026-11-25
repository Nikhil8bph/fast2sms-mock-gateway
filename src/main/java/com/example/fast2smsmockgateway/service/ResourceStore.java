package com.example.fast2smsmockgateway.service;

import com.example.fast2smsmockgateway.dtos.common.SandboxResource;
import com.example.fast2smsmockgateway.facade.ResourceFacade;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ResourceStore {
    private final ResourceFacade facade;
    private final CallRecorder recorder;

    public ResourceStore(ResourceFacade facade, CallRecorder recorder) {
        this.facade = facade;
        this.recorder = recorder;
    }

    public SandboxResource save(String account, String kind, String scope, String reference, Map<String, Object> data) {
        return save(account, kind, scope, reference, data, null);
    }

    public SandboxResource save(String account, String kind, String scope, String reference,
            Map<String, Object> data, byte[] content) {
        return facade.save(new SandboxResource(key(account, kind, scope, reference), account, kind, scope,
                reference, recorder.encode(data), content));
    }

    public Map<String, Object> reserve(String account, String kind, String scope, String reference) {
        return data(facade.reserve(new SandboxResource(key(account, kind, scope, reference), account, kind,
                scope, reference, "{}", null)));
    }

    public Optional<SandboxResource> find(String account, String kind, String scope, String reference) {
        return facade.find(key(account, kind, scope, reference));
    }

    public Optional<SandboxResource> lock(String account, String kind, String scope, String reference) {
        return facade.lock(key(account, kind, scope, reference));
    }

    public Optional<SandboxResource> findByReference(String account, String kind, String reference) {
        return facade.findByReference(account, kind, reference);
    }

    public List<SandboxResource> list(String account, String kind, String scope) {
        return facade.list(account, kind, scope);
    }

    public void delete(SandboxResource resource) {
        facade.delete(resource);
    }

    public Map<String, Object> data(SandboxResource resource) {
        return recorder.decode(resource.payload());
    }

    public String numericId() {
        return Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
    }

    private String key(String account, String kind, String scope, String reference) {
        return UUID.nameUUIDFromBytes(String.join("\u0000", account, kind, scope, reference)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
