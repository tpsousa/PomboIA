package com.pombo.endpoint;

import com.pombo.endpoint.dto.CreateEndpointRequest;
import com.pombo.endpoint.dto.EndpointCreatedResponse;
import com.pombo.endpoint.dto.EndpointResponse;
import com.pombo.shared.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * ATENÇÃO (Fase 2): o usuário vem do cabeçalho X-User-Id apenas para podermos testar
 * antes de existir autenticação. Na Fase 3 isto passa a vir do JWT.
 */
@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointService service;

    public EndpointController(EndpointService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EndpointCreatedResponse create(@RequestHeader("X-User-Id") UUID userId,
                                          @Valid @RequestBody CreateEndpointRequest request) {
        return service.create(userId, request);
    }

    @GetMapping
    public PageResponse<EndpointResponse> list(
            @RequestHeader("X-User-Id") UUID userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(userId, pageable);
    }

    @GetMapping("/{id}")
    public EndpointResponse get(@RequestHeader("X-User-Id") UUID userId, @PathVariable UUID id) {
        return service.get(userId, id);
    }
}