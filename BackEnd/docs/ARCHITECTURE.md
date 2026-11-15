# Arquitetura — BackEnd (legado)

Backend **legado** do Social Flux, em migração domínio a domínio para serviços Spring Boot. **Congelado:** só correções;
nada de funcionalidade nova aqui. Hoje cuida **só de notificações**. Usuários/perfil e autenticação saíram para o `profile_service` e o
`authentication_service`; relações para o `social_graph_service`; postagens, curtidas e imagens para o `post_service`.
Javalin 6 (Jetty) · Java 17 (imagem Docker com JDK/JRE 21) · JDBC puro + HikariCP · MySQL 8.4 · Log4j2 · porta **8000**.

Análise de falhas e plano de migração: [`../../docs/analise-backend-e-migracao-spring-boot.md`](../../docs/analise-backend-e-migracao-spring-boot.md).
Visão do monorepo: [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md).

## Como funciona hoje

```
Caddy (forward_auth no authentication_service) ── X-User-Id ──► Javalin :8000
   Controller (métodos estáticos; AuthMiddleware.AuthValidate chamado em cada handler)
     └─► Service (obtido por ctx.appData(Keys.X))
           └─► DAO (JDBC, PreparedStatement) ─► MySQL
                    ▲
       uma única java.sql.Connection retirada do pool HikariCP e compartilhada por todos
```

- `AUTH_MODE=gateway` (usado no compose e nos scripts): o serviço **não valida token**; só aceita o id inteiro do usuário em
  `X-User-Id`, que o gateway define. Por isso a porta nunca é publicada. O modo `legacy` (JWT HS256 próprio) é código morto.
- A tabela `usuario` do MySQL é uma **projeção somente leitura** do perfil, escrita pelo `profile_service` (via RabbitMQ) e usada nos JOINs.
- Configuração: `application.properties` (se existir no classpath) sobrescrito por variáveis de ambiente (`PropertiesUtil`).
- Sem testes automatizados.

## Rotas (todas exigem `X-User-Id`)

| Rota | Controller |
| --- | --- |
| `GET /api/notification` (`?userId=`, `&count=true` conta as não vistas), `POST /api/notification`, `PATCH /api/notification/{id_receiver}` | `NotificationController` |

## Árvore de diretórios

```
BackEnd/
├── Dockerfile                    build Maven (JDK 21) → jar-with-dependencies em JRE 21 Alpine, usuário sem root
├── .dockerignore                 exclui target, logs, .idea, lib e o application.properties local
├── .gitignore                    ignora target/, arquivos de IDE e src/main/resources/ (mas os arquivos abaixo já estavam versionados)
├── pom.xml                       Javalin, Jackson, HikariCP, MySQL connector, jjwt, jBCrypt, Log4j2, commons-validator (+ mongodb, javalin-rendering, JUnit/Mockito sem uso)
├── dependency-reduced-pom.xml    (não versionado) artefato gerado por build; pode ser apagado
├── docs/ARCHITECTURE.md          este arquivo
├── lib/mysql-connector-j-8.3.0.jar   jar do conector versionado à mão; não é usado (o pom baixa o conector)
├── logs/app.log                  log do Log4j2 — VERSIONADO por engano (não commitar mudanças)
├── .idea/                        configuração parcial do IntelliJ (versionada)
├── target/                       (não versionado) saída do Maven
└── src/main/
    ├── java/com/backend/
    │   ├── controllers/          handlers HTTP (Javalin)
    │   ├── services/             regras de negócio (finas)
    │   ├── dao/                  acesso ao MySQL via JDBC
    │   ├── database/             pool de conexões
    │   ├── model/entities/       entidades serializadas direto em JSON
    │   ├── model/enums/          enums do domínio
    │   └── util/                 autenticação, configuração
    └── resources/
        ├── application.properties    VERSIONADO por engano, contém segredo antigo (jwt.secret) — não editar/commitar
        └── log4j2.properties         log no console e em logs/app.log
```

## Arquivos

### Raiz do pacote `com.backend`

| Arquivo | O que representa |
| --- | --- |
| `App.java` | `main`: sobe o Javalin, abre a conexão ao iniciar o servidor, registra o service de notificações em `appData`, configura CORS e as rotas de notificação |
| `Keys.java` | Enum de chaves tipadas do `appData` do Javalin (conexão SQL e o service de notificações) |
| `Mensagem.java` | Resposta padrão `{ mensagem, sucesso }` |

### `controllers/`

| Arquivo | O que representa |
| --- | --- |
| `NotificationController.java` | Listar notificações/contar não vistas, criar notificação (`id_post` aceito só se for um UUID) e marcar como vistas |

### `services/`

| Arquivo | O que representa |
| --- | --- |
| `NotificationService.java` | Regras de notificação sobre o `NotificationDao` |

### `dao/`

| Arquivo | O que representa |
| --- | --- |
| `NotificationDao.java` | SQL da tabela `notification` |

### `database/`

| Arquivo | O que representa |
| --- | --- |
| `SQLRepository.java` | Cria o `HikariDataSource` (URL/usuário/senha das propriedades, até 10 conexões) — mas o `App` usa só uma conexão compartilhada |

### `model/`

| Arquivo | O que representa |
| --- | --- |
| `entities/Usuario.java` | Usuário (espelha a projeção `usuario`; ainda tem campos antigos como `senha`/`email`) |
| `entities/Notification.java` | Notificação: remetente, destinatário, título, descrição, categoria, postagem relacionada (`id_post`: UUID do post_service, texto) e se foi vista |
| `enums/NotificationCategory.java` | `LIKE`, `COMENT`, `FOLLOW`, `GENERAL` (com id numérico) |

### `util/`

| Arquivo | O que representa |
| --- | --- |
| `AuthMiddleware.java` | `AuthValidate` (exige `X-User-Id` no modo gateway ou JWT no modo legado) e `currentUser` (usuário da requisição) |
| `AuthMode.java` | Lê `AUTH_MODE` (`gateway` ou `legacy`) |
| `PropertiesUtil.java` | Carrega `application.properties` e aplica as variáveis de ambiente (`PORTA_SERVIDOR`, `SQLDB_*`, `AUTH_MODE`, `CORS_ALLOWED_ORIGIN`…) |
| `JWT.java` | JWT HS256 próprio — só usado no modo `legacy` (código morto em produção) |
| `AuthCookie.java` | Define/limpa o cookie `token` e lê o Bearer do cabeçalho ou cookie — só usado no modo `legacy` |
