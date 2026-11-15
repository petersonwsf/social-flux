package com.backend;

import com.backend.controllers.*;
import com.backend.database.SQLRepository;
import com.backend.services.*;
import com.backend.util.AuthMode;
import com.backend.util.PropertiesUtil;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.function.Consumer;

public class App {

    private static final Logger logger = LogManager.getLogger();

    private static final String PORTA_SERVIDOR = "porta.servidor";
    private static final String SQL_URL = "sqldb.url";
    private static final String USERNAME = "sqldb.username";
    private static final String PASSWORD = "sqldb.password";

    private final Properties properties;
    private Connection connection;

    public App() {this.properties = PropertiesUtil.getProperties();}

    public void iniciar() {
        Javalin app = iniciarJavalin();
        configurarRotas(app);

        app.exception(Exception.class, (e, ctx) ->{
            logger.error("Erro não tratado {}",e);
            ctx.status(500);
        });

    }

    private void registrarServicos(JavalinConfig config, Connection connection) {

        config.appData(Keys.NOTIFICATION_SERVICE.key(), new NotificationService(connection));
    }

    private Javalin iniciarJavalin() {
        int porta = getPortaServidor();
        logger.info("Iniciando aplicação na porta {}", porta);
        Consumer<JavalinConfig> configConsumer = this::configureJavalin;
        Javalin app = Javalin.create(configConsumer).start(porta);
        logger.info("Servidor inicado com sucesso");
        return app;
    }

    private void configureJavalin(JavalinConfig config) {

        config.events(event -> {
           event.serverStarted(() -> {
               connection = iniciarSQLRepository();
               config.appData(Keys.SQL_DB.key(), connection);
               registrarServicos(config, connection);
           });
           event.serverStopped(() -> {
               if (connection == null) {
                   logger.error("A conexão não devia estar nula ao encerrar a aplicação");
               } else {
                   connection.close();
                   logger.info("Conexão encerrada com sucesso");
               }
           });
        });

    }

    private Connection iniciarSQLRepository() throws SQLException {
        logger.info("Conectando ao banco de dados SQL");
        Connection connection = SQLRepository.getDataSource().getConnection();
        if (connection == null) {
            logger.error("Não foi possível recuperar a conexão com o banco de dados");
            System.exit(1);
        }
        return connection;
    }

    private int getPortaServidor() {
        int porta = 8000;
        if (properties.containsKey(PORTA_SERVIDOR)) {
            try {
                return Integer.parseInt(properties.getProperty(PORTA_SERVIDOR));
            } catch (NumberFormatException exception) {
                logger.error("A porta do servidor não é um valor válido ", exception);
                System.exit(1);
            }
        } else {
            logger.error("Porta não definida no arquivo application.properties");
        }
        return porta;
    }

    private void configurarRotas(Javalin app) {



        // Com cookies (credenciais) a origem permitida precisa ser exata, nunca "*".
        String origemPermitida = PropertiesUtil.getProperties().getProperty("cors.allowed-origin", "http://165.227.76.216");

        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", origemPermitida);
            ctx.header("Vary", "Origin");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.header("Access-Control-Allow-Credentials", "true");
        });

        app.options("/*", ctx -> ctx.status(200));

        // USUÁRIOS / PERFIL / FOTO DE PERFIL e AUTENTICAÇÃO (login, cadastro, sessão):
        // migrados para o profile_service e o authentication_service. O gateway (Caddy) nem encaminha essas rotas para cá.
        // A tabela `usuario` deste banco é só uma PROJEÇÃO de leitura do perfil (usada nos JOINs das notificações).

        // RELAÇÕES (social_graph_service), POSTAGENS, CURTIDAS e IMAGENS DE POSTAGEM (post_service): migrados; as rotas
        // saíram daqui. Só as notificações continuam neste backend até o domínio migrar.

        //NOTIFICATION
        app.get("/api/notification", NotificationController::getUserNotifications);
        app.post("/api/notification", NotificationController::createNotification);
        app.patch("/api/notification/{id_receiver}", NotificationController::viewNotifications);


    }

    public static void main(String[] args) {
        try {
            logger.info("Iniciando programa");
            new App().iniciar();
        } catch (Exception exception) {
            logger.error("Erro ao iniciar a aplicação ", exception);
            System.exit(1);
        }
    }
}