package com.socialflux.graph.legacy;

import com.socialflux.graph.config.GraphProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Escreve a PROJEÇÃO das relações ACEITAS na tabela {@code relacao} do MySQL legado, que o feed do backend Javalin ainda
 * usa ({@code JOIN relacao ... aceito = 1}). TEMPORÁRIO: some quando Postagens migrar.
 * <p>O pool do MySQL fica encapsulado aqui (não é um bean {@code DataSource}, para não desligar o DataSource principal).
 * As duas operações são idempotentes: repetir não muda o resultado.
 */
@Component
@ConditionalOnProperty(prefix = "graph.legacy", name = "enabled", havingValue = "true")
public class LegacyRelacaoWriter implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;

    public LegacyRelacaoWriter(GraphProperties properties) {
        GraphProperties.Legacy legacy = properties.legacy();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(legacy.url());
        config.setUsername(legacy.username());
        config.setPassword(legacy.password());
        config.setMaximumPoolSize(3);
        config.setPoolName("legacy-mysql-relacao-projection");
        config.setInitializationFailTimeout(-1);   // o serviço sobe mesmo se o MySQL ainda não estiver pronto
        this.dataSource = new HikariDataSource(config);
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** {@code seguidor} segue {@code seguido} (ids inteiros legados). A FK exige que os dois já estejam em {@code usuario}. */
    public void link(int seguidor, int seguido) {
        jdbc.sql("insert into relacao (id_seguidor, id_seguido, aceito) values (:seguidor, :seguido, 1) "
                        + "on duplicate key update aceito = 1")
                .param("seguidor", seguidor).param("seguido", seguido).update();
    }

    public void unlink(int seguidor, int seguido) {
        jdbc.sql("delete from relacao where id_seguidor = :seguidor and id_seguido = :seguido")
                .param("seguidor", seguidor).param("seguido", seguido).update();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
