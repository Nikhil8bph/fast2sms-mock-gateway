package com.example.fast2smsmockgateway.mapper;

import com.example.fast2smsmockgateway.dtos.common.SandboxResource;
import com.example.fast2smsmockgateway.entities.SandboxResourceEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ResourceMapper {
    SandboxResourceEntity toEntity(SandboxResource dto);
    SandboxResource toDto(SandboxResourceEntity entity);
}
