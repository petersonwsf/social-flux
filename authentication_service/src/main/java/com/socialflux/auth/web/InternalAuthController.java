package com.socialflux.auth.web;

import com.socialflux.auth.security.AuthenticatedAccount;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de {@code forward_auth} do gateway (Caddy). Valida o token (assinatura, validade, audiência e revogação)
 * e devolve a identidade em cabeçalhos para os serviços legados:
 * <ul>
 *   <li>{@code X-Account-Id}: UUID da conta ({@code sub});</li>
 *   <li>{@code X-User-Id}: id inteiro do perfil legado (TEMPORÁRIO) — ausente se a conta não tem perfil legado.</li>
 * </ul>
 * NÃO deve ser acessível pela internet: o Caddy bloqueia {@code /internal/*}.
 */
@RestController
public class InternalAuthController {

    @GetMapping("/internal/auth/verify")
    ResponseEntity<Void> verify(@AuthenticationPrincipal AuthenticatedAccount principal) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.NO_CONTENT);
        response.header("X-Account-Id", principal.accountId().toString());
        if (principal.legacyUserId() != null) {
            response.header("X-User-Id", String.valueOf(principal.legacyUserId()));
        }
        return response.build();
    }
}
