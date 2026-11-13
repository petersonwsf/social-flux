package com.socialflux.graph.domain;

import com.socialflux.graph.web.ApiException;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Como o cliente se refere a um usuário na URL: o UUID da conta ou, durante a transição, o id inteiro legado
 * (o frontend ainda usa o inteiro). Exatamente um dos dois está preenchido.
 */
public record UserRef(UUID uuid, Integer legacyId) {

    private static final Pattern DIGITS = Pattern.compile("^\\d{1,9}$");

    public static UserRef parse(String raw) {
        if (raw != null && DIGITS.matcher(raw).matches()) {
            return new UserRef(null, Integer.parseInt(raw));
        }
        try {
            return new UserRef(UUID.fromString(raw), null);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("parametro_invalido", "Identificador de usuário inválido");
        }
    }
}
