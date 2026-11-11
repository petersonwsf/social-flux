package com.backend.util;

import com.backend.App;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * Configuração do backend legado: lê {@code application.properties} (se existir) e deixa as variáveis de ambiente
 * sobrescreverem cada chave. Em container (docker compose) só variáveis de ambiente são usadas — nenhum segredo
 * precisa estar no arquivo nem na imagem.
 */
public class PropertiesUtil {

    private static final Logger logger = LogManager.getLogger();

    /** chave da propriedade -> variável de ambiente. */
    private static final Map<String, String> ENV_OVERRIDES = Map.of(
            "porta.servidor", "PORTA_SERVIDOR",
            "sqldb.url", "SQLDB_URL",
            "sqldb.username", "SQLDB_USERNAME",
            "sqldb.password", "SQLDB_PASSWORD",
            "jwt.secret", "JWT_SECRET",
            "cors.allowed-origin", "CORS_ALLOWED_ORIGIN",
            "cookie.secure", "COOKIE_SECURE",
            "cookie.same-site", "COOKIE_SAME_SITE",
            "auth.mode", "AUTH_MODE"
    );

    public static Properties getProperties() {
        Properties props = new Properties();
        try (InputStream input = App.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                props.load(input);
            } else if (System.getenv("SQLDB_URL") == null) {
                logger.error("Arquivo application.properties não encontrado e SQLDB_URL não definida");
                System.exit(1);
            }
        } catch (IOException exception) {
            logger.error("Erro ao carregar o arquivo de propriedades /src/main/resources/application.properties", exception);
            System.exit(1);
        }

        ENV_OVERRIDES.forEach((property, variable) -> {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) {
                props.setProperty(property, value);
            }
        });
        return props;
    }
}
