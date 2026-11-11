package com.backend.util;

/**
 * Modo de autenticação do backend legado.
 * <ul>
 *   <li>{@code legacy} (padrão): valida o JWT HS256 próprio e expõe /login, /register, /logout e /api/me;</li>
 *   <li>{@code gateway}: a autenticação é feita pelo authentication_service. O gateway (Caddy, forward_auth) valida o
 *       token e repassa o id do usuário em {@code X-User-Id}. Este serviço só aceita esse cabeçalho e NÃO deve ser
 *       acessível diretamente da internet.</li>
 * </ul>
 */
public final class AuthMode {

    private static final boolean GATEWAY =
            "gateway".equalsIgnoreCase(PropertiesUtil.getProperties().getProperty("auth.mode", "legacy"));

    private AuthMode() {
    }

    public static boolean isGateway() {
        return GATEWAY;
    }
}
