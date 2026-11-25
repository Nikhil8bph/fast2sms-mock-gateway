package com.example.fast2smsmockgateway.repo;

import com.example.fast2smsmockgateway.entities.OtpChallengeEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface OtpChallengeRepository extends JpaRepository<OtpChallengeEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from OtpChallengeEntity challenge where challenge.id = :id")
    Optional<OtpChallengeEntity> lockById(String id);
}
