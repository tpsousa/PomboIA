package com.pombo.endpoint;

import com.pombo.endpoint.dto.CreateEndpointRequest;
import com.pombo.endpoint.dto.EndpointCreatedResponse;
import com.pombo.endpoint.dto.EndpointResponse;
import com.pombo.shared.ConflictException;
import com.pombo.shared.NotFoundException;
import com.pombo.shared.PageResponse;
import com.pombo.shared.SecretCipher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class EndpointService {

    private static final int MAX_ACTIVE_ENDPOINTS = 10;

    private final WebhookEndpointRepository repository;
    private final SecretCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public EndpointService(WebhookEndpointRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Transactional
    public EndpointCreatedResponse create(UUID userId, CreateEndpointRequest request) {
        if (repository.countByUserIdAndActiveTrue(userId) >= MAX_ACTIVE_ENDPOINTS) {
            throw new ConflictException("Limite de " + MAX_ACTIVE_ENDPOINTS + " destinos ativos atingido.");
        }

        String secret = generateSecret();
        WebhookEndpoint endpoint = new WebhookEndpoint(
                userId,
                request.url(),
                request.description(),
                request.eventTypes().toArray(String[]::new),
                cipher.encrypt(secret));

        repository.saveAndFlush(endpoint);
        return EndpointCreatedResponse.from(endpoint, secret);
    }

    @Transactional(readOnly = true)
    public PageResponse<EndpointResponse> list(UUID userId, Pageable pageable) {
        return PageResponse.of(repository.findByUserId(userId, pageable), EndpointResponse::from);
    }

    @Transactional(readOnly = true)
    public EndpointResponse get(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .map(EndpointResponse::from)
                .orElseThrow(() -> new NotFoundException("Destino não encontrado."));
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}