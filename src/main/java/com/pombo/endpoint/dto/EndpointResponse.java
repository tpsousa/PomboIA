package com.pombo.endpoint.dto;

import com.pombo.endpoint.WebhookEndpoint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Visão padrão do destino. Nunca contém o segredo. */
public record EndpointResponse(
        UUID id,
        String url,
        String description,
        List<String> eventTypes,
        boolean active,
        Instant createdAt
) {
    public static EndpointResponse from(WebhookEndpoint e) {
        return new EndpointResponse(e.getId(), e.getUrl(), e.getDescription(),
                List.of(e.getEventTypes()), e.isActive(), e.getCreatedAt());
    }
}