package com.pombo.endpoint.dto;

import com.pombo.endpoint.WebhookEndpoint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Resposta do cadastro: é a ÚNICA vez em que o segredo em texto aparece. */
public record EndpointCreatedResponse(
        UUID id,
        String url,
        String description,
        List<String> eventTypes,
        boolean active,
        String secret,
        Instant createdAt
) {
    public static EndpointCreatedResponse from(WebhookEndpoint e, String secret) {
        return new EndpointCreatedResponse(e.getId(), e.getUrl(), e.getDescription(),
                List.of(e.getEventTypes()), e.isActive(), secret, e.getCreatedAt());
    }
}