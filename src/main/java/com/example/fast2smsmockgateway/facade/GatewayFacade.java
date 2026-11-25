package com.example.fast2smsmockgateway.facade;

import com.example.fast2smsmockgateway.dtos.common.ApiCall;
import com.example.fast2smsmockgateway.dtos.common.OtpChallenge;
import com.example.fast2smsmockgateway.entities.ApiCallEntity;
import com.example.fast2smsmockgateway.mapper.PersistenceMapper;
import com.example.fast2smsmockgateway.repo.ApiCallRepository;
import com.example.fast2smsmockgateway.repo.OtpChallengeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class GatewayFacade {
    private final ApiCallRepository calls;
    private final OtpChallengeRepository challenges;
    private final PersistenceMapper mapper;

    public GatewayFacade(ApiCallRepository calls, OtpChallengeRepository challenges,
            PersistenceMapper mapper) {
        this.calls = calls;
        this.challenges = challenges;
        this.mapper = mapper;
    }

    public ApiCall enqueue(ApiCall dto) {
        ApiCallEntity entity = mapper.toEntity(dto);
        entity.markNew();
        return mapper.toDto(calls.save(entity));
    }

    public ApiCall update(ApiCall dto) {
        return mapper.toDto(calls.save(mapper.toEntity(dto)));
    }

    public Optional<ApiCall> lockCall(String id) {
        return calls.lockById(id).map(mapper::toDto);
    }

    public List<String> readyIds(Instant now) {
        return calls.readyIds(now, PageRequest.of(0, 20));
    }

    public List<ApiCall> findCampaign(String requestId, String account, String channel) {
        return calls.findByRequestIdAndAccountAndChannelOrderByCreatedAtAscIdAsc(requestId, account, channel)
                .stream().map(mapper::toDto).toList();
    }

    public List<ApiCall> findCalls(String account, String channel, Instant from, Instant to) {
        return calls
                .findByAccountAndChannelAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
                        account, channel, from, to)
                .stream().map(mapper::toDto).toList();
    }

    public List<ApiCall> recentOtpSends(String account, String mobile, Instant since) {
        return calls.recentOtpSends(account, mobile, since).stream().map(mapper::toDto).toList();
    }

    public Optional<OtpChallenge> lockChallenge(String id) {
        return challenges.lockById(id).map(mapper::toDto);
    }

    public OtpChallenge saveChallenge(OtpChallenge dto, boolean isNew) {
        var entity = mapper.toEntity(dto);
        if (isNew) {
            entity.markNew();
        }
        return mapper.toDto(challenges.save(entity));
    }
}
