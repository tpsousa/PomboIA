package com.pombo.endpoint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateEndpointRequest(

        @NotBlank
        @Size(max = 2048)
        @Pattern(regexp = "^https?://.+", message = "deve começar com http:// ou https://")
        String url,

        @Size(max = 255)
        String description,

        @NotEmpty
        @Size(max = 20, message = "no máximo 20 tipos de evento")
        List<@NotBlank @Pattern(regexp = "^(\\*|[a-z0-9_.-]{1,64})$",
                message = "use '*' ou letras minúsculas, números, '.', '_' e '-'") String> eventTypes
) { }