package com.socialflux.graph.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** Toda a configuração do serviço, vinda de variáveis de ambiente (ver application.yml). Falha rápida se inválida. */
@Validated
@ConfigurationProperties(prefix = "graph")
public record GraphProperties(
        @Valid @NotNull Security security,
        @DefaultValue List<String> allowedOrigins,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Legacy legacy) {

    public record Security(
            @NotBlank String jwksUri,
            @NotBlank String issuer,
            @NotBlank String audience,
            @DefaultValue("PT60S") Duration clockSkew) {
    }

    /** Limites antiabuso (D8). Valores altos de propósito: o objetivo é conter automação, não o uso normal. */
    public record Limits(
            @Min(1) int followActionsPerHour,
            @Min(1) long maxFollowing,
            @Min(1) long maxPendingSent) {
    }

    /** Projeção temporária das relações aceitas na tabela {@code relacao} do MySQL legado (feed do legado). */
    public record Legacy(boolean enabled, String url, String username, String password) {
    }
}
