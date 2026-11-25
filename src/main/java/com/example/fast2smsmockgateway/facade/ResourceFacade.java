package com.example.fast2smsmockgateway.facade;

import com.example.fast2smsmockgateway.dtos.common.SandboxResource;
import com.example.fast2smsmockgateway.mapper.ResourceMapper;
import com.example.fast2smsmockgateway.repo.ResourceRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ResourceFacade {
    private final ResourceRepository resources;
    private final ResourceMapper mapper;

    public ResourceFacade(ResourceRepository resources, ResourceMapper mapper) {
        this.resources = resources;
        this.mapper = mapper;
    }

    public SandboxResource save(SandboxResource dto) {
        return mapper.toDto(resources.save(mapper.toEntity(dto)));
    }

    public SandboxResource reserve(SandboxResource dto) {
        var entity = mapper.toEntity(dto);
        resources.reserve(entity.key, entity.account, entity.kind, entity.scope, entity.reference);
        return mapper.toDto(resources.lockByKey(entity.key).orElseThrow());
    }

    public Optional<SandboxResource> find(String key) {
        return resources.findById(key).map(mapper::toDto);
    }

    public Optional<SandboxResource> lock(String key) {
        return resources.lockByKey(key).map(mapper::toDto);
    }

    public Optional<SandboxResource> findByReference(String account, String kind, String reference) {
        return resources.findByAccountAndKindAndReference(account, kind, reference).map(mapper::toDto);
    }

    public List<SandboxResource> list(String account, String kind, String scope) {
        return resources.findAllByAccountAndKindAndScopeOrderByReference(account, kind, scope)
                .stream().map(mapper::toDto).toList();
    }

    public void delete(SandboxResource resource) {
        resources.delete(mapper.toEntity(resource));
    }
}
