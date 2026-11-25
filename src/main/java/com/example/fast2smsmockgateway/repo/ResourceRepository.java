package com.example.fast2smsmockgateway.repo;

import com.example.fast2smsmockgateway.entities.SandboxResourceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<SandboxResourceEntity, String> {
    List<SandboxResourceEntity> findAllByAccountAndKindAndScopeOrderByReference(String account, String kind, String scope);

    Optional<SandboxResourceEntity> findByAccountAndKindAndReference(String account, String kind, String reference);

    @Modifying
    @Query(value = """
            INSERT INTO sandbox_resource (resource_key, account, kind, resource_scope, external_id, payload)
            VALUES (:key, :account, :kind, :scope, :reference, '{}')
            ON DUPLICATE KEY UPDATE resource_key = resource_key
            """, nativeQuery = true)
    void reserve(String key, String account, String kind, String scope, String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select resource from SandboxResourceEntity resource where resource.key = :key")
    Optional<SandboxResourceEntity> lockByKey(String key);
}
