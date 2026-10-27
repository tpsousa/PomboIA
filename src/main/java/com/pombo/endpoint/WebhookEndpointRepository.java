package com.pombo.endpoint;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    Page<WebhookEndpoint> findByUserId(UUID userId, Pageable pageable);

    /** Filtrar por dono na própria consulta: recurso alheio simplesmente "não existe". */
    Optional<WebhookEndpoint> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndActiveTrue(UUID userId);
}