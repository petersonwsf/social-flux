package com.backend.util;

import io.javalin.http.Context;

import java.util.Properties;

/**
 * Gerencia o JWT em um cookie HttpOnly (inacessível ao JavaScript do navegador).
 *
 * Propriedades opcionais em application.properties:
 *   cookie.secure=true        -> envia o cookie só por HTTPS (use em produção)
 *   cookie.same-site=Lax      -> Lax (padrão), Strict ou None (None exige secure=true)
 */
public class AuthCookie {

    public static final String COOKIE_NAME = "token";
    private static final int MAX_AGE_SECONDS = 60 * 60 * 48; // mesma validade do JWT (48h)

    private static final Properties PROPERTIES = PropertiesUtil.getProperties();
    private static final boolean SECURE = Boolean.parseBoolean(PROPERTIES.getProperty("cookie.secure", "false"));
    private static final String SAME_SITE = PROPERTIES.getProperty("cookie.same-site", "Lax");

    private AuthCookie() {
    }

    /** Grava o JWT no cookie HttpOnly da resposta. */
    public static void set(Context ctx, String jwt) {
        ctx.header("Set-Cookie", build(jwt, MAX_AGE_SECONDS));
    }

    /** Remove o cookie (logout). */
    public static void clear(Context ctx) {
        ctx.header("Set-Cookie", build("", 0));
    }

    /**
     * Devolve o token no formato "Bearer xxx" a partir do cookie ou, como alternativa
     * (clientes de API), do header Authorization. Retorna null se não houver token.
     */
    public static String resolveBearer(Context ctx) {
        String cookie = ctx.cookie(COOKIE_NAME);
        if (cookie != null && !cookie.isBlank()) {
            return "Bearer " + cookie;
        }
        return ctx.header("Authorization");
    }

    private static String build(String value, int maxAge) {
        StringBuilder cookie = new StringBuilder()
                .append(COOKIE_NAME).append('=').append(value)
                .append("; Path=/")
                .append("; Max-Age=").append(maxAge)
                .append("; HttpOnly")
                .append("; SameSite=").append(SAME_SITE);
        if (SECURE) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }
}
