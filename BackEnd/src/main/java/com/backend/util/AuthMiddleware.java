package com.backend.util;

import com.backend.Mensagem;
import com.backend.model.entities.Usuario;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AuthMiddleware {

    private static final Logger logger = LogManager.getLogger();

    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Id do usuário repassado pelo gateway (modo gateway), ou null se ausente/inválido. */
    public static Integer gatewayUserId(Context ctx) {
        String header = ctx.header("X-User-Id");
        if (header == null || !header.matches("[0-9]{1,9}")) return null;
        return Integer.valueOf(header);
    }

    /** Usuário autenticado da requisição (no modo gateway só o id é conhecido; o resto vem do banco). */
    public static Usuario currentUser(Context ctx) {
        if (AuthMode.isGateway()) {
            Integer id = gatewayUserId(ctx);
            if (id == null) throw new UnauthorizedResponse("Não autenticado");
            Usuario usuario = new Usuario();
            usuario.setId(id);
            return usuario;
        }
        try {
            return mapper.readValue(JWT.getToken(AuthCookie.resolveBearer(ctx)), Usuario.class);
        } catch (Exception e) {
            throw new UnauthorizedResponse("Não autorizado!");
        }
    }

    public static void AuthValidate(Context ctx) {

        if (AuthMode.isGateway()) {
            if (gatewayUserId(ctx) == null) {
                ctx.status(401).json(new Mensagem("Não autenticado", false));
                throw new UnauthorizedResponse("Não autenticado");
            }
            return;
        }

        String bearerToken = AuthCookie.resolveBearer(ctx);

        try {
            if (!JWT.validateToken(bearerToken)) {
                ctx.status(401).json(new Mensagem("Token não fornecido", false));
                throw new UnauthorizedResponse();
            }
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedResponse("Token expirado!");
        } catch (SignatureException e) {
            throw new UnauthorizedResponse("Token inválido!");
        } catch (MalformedJwtException e) {
            throw new UnauthorizedResponse("Token mal formado");
        } catch (UnauthorizedResponse e) {
            throw new UnauthorizedResponse("Não autorizado!");
        } catch (Exception e) {
            logger.error("Erro não tratado: " + e);
            ctx.status(500).json(new Mensagem("Erro não tratado!" + e, false));
            throw new RuntimeException();
        }

    }
}
