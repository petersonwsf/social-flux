package com.socialflux.profile.legacy;

import com.socialflux.profile.config.ProfileProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Escreve a PROJEÇÃO do perfil na tabela {@code usuario} do MySQL legado, que o backend Javalin ainda usa nos JOINs
 * (postagens, relações, notificações). TEMPORÁRIO: some quando esses domínios migrarem.
 * <p>O pool do MySQL fica encapsulado aqui (não é um bean {@code DataSource}, para não desligar o DataSource principal).
 * A coluna {@code profile_version} protege contra eventos antigos/duplicados: só uma versão MAIOR sobrescreve.
 */
@Component
@ConditionalOnProperty(prefix = "profile.legacy", name = "enabled", havingValue = "true")
public class LegacyUsuarioWriter implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;

    public LegacyUsuarioWriter(ProfileProperties properties) {
        ProfileProperties.Legacy legacy = properties.legacy();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(legacy.url());
        config.setUsername(legacy.username());
        config.setPassword(legacy.password());
        config.setMaximumPoolSize(3);
        config.setPoolName("legacy-mysql-projection");
        config.setInitializationFailTimeout(-1);   // o serviço sobe mesmo se o MySQL ainda não estiver pronto
        this.dataSource = new HikariDataSource(config);
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void upsert(int id, String username, String nome, String biografia, String fotoPerfil, boolean privado,
                       LocalDate criadoEm, long version) {
        int updated = jdbc.sql("update usuario set username = :username, nome = :nome, biografia = :bio, "
                        + "foto_perfil = :foto, privado = :privado, profile_version = :version "
                        + "where id = :id and profile_version < :version")
                .param("username", username).param("nome", nome).param("bio", biografia)
                .param("foto", fotoPerfil).param("privado", privado).param("version", version).param("id", id)
                .update();
        if (updated > 0) {
            return;
        }
        Integer exists = jdbc.sql("select count(*) from usuario where id = :id").param("id", id)
                .query(Integer.class).single();
        if (exists != null && exists > 0) {
            return; // versão igual ou mais nova já aplicada
        }
        jdbc.sql("insert into usuario (id, username, nome, biografia, foto_perfil, privado, data_criacao, profile_version) "
                        + "values (:id, :username, :nome, :bio, :foto, :privado, :criado, :version)")
                .param("id", id).param("username", username).param("nome", nome).param("bio", biografia)
                .param("foto", fotoPerfil).param("privado", privado).param("criado", criadoEm).param("version", version)
                .update();
    }

    public void setPrivate(int id, boolean privado, long version) {
        jdbc.sql("update usuario set privado = :privado, profile_version = :version "
                        + "where id = :id and profile_version <= :version")
                .param("privado", privado).param("version", version).param("id", id).update();
    }

    public void delete(int id) {
        jdbc.sql("delete from usuario where id = :id").param("id", id).update();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
