package com.backend.database;

import com.backend.util.PropertiesUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.Properties;

public class SQLRepository {

    private static final HikariDataSource dataSource;

    static {

        Properties properties = PropertiesUtil.getProperties();

        String url = properties.getProperty("sqldb.url");
        String username = properties.getProperty("sqldb.username");
        String password = properties.getProperty("sqldb.password");

        if (url == null || username == null || password == null) throw new IllegalArgumentException("Defina as variáveis de conexão ao banco de dados");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);

        config.setMaximumPoolSize(10);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setConnectionTimeout(60000);
        config.setConnectionTestQuery("SELECT 1");

        dataSource = new HikariDataSource(config);
    }

    public static DataSource getDataSource() {
        return dataSource;
    }
}
