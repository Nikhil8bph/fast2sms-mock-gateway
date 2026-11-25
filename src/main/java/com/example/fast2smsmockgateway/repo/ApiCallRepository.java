package com.example.fast2smsmockgateway.repo;

import com.example.fast2smsmockgateway.entities.ApiCallEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApiCallRepository extends JpaRepository<ApiCallEntity, String> {
    List<ApiCallEntity> findByRequestIdAndAccountAndChannelOrderByCreatedAtAscIdAsc(
            String requestId, String account, String channel);

    List<ApiCallEntity> findByAccountAndChannelAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
            String account, String channel, Instant from, Instant to);

    @Query("""
            select call from ApiCallEntity call
            where call.account = :account and call.channel = 'OTP' and call.recipients = :mobile
                and call.createdAt >= :since
            order by call.createdAt desc, call.id desc
            """)
    List<ApiCallEntity> recentOtpSends(String account, String mobile, Instant since);

    @Query("""
            select call.id from ApiCallEntity call
            where call.status in ('PENDING', 'SENDING') and call.dueAt <= :now
            order by call.dueAt, call.id
            """)
    List<String> readyIds(Instant now, Pageable limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select call from ApiCallEntity call where call.id = :id")
    Optional<ApiCallEntity> lockById(String id);
}
