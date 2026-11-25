package com.example.fast2smsmockgateway.mapper;

import com.example.fast2smsmockgateway.dtos.common.ApiCall;
import com.example.fast2smsmockgateway.dtos.common.OtpChallenge;
import com.example.fast2smsmockgateway.entities.ApiCallEntity;
import com.example.fast2smsmockgateway.entities.OtpChallengeEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PersistenceMapper {
    ApiCallEntity toEntity(ApiCall dto);
    ApiCall toDto(ApiCallEntity entity);
    OtpChallengeEntity toEntity(OtpChallenge dto);
    OtpChallenge toDto(OtpChallengeEntity entity);
}
