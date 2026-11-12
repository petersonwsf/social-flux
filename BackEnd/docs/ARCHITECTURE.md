# Arquitetura — BackEnd (legado)

Backend **legado** do Social Flux, em migração domínio a domínio para serviços Spring Boot. **Congelado:** só correções;
nada de funcionalidade nova aqui. Hoje cuida de **relações (seguir), postagens/comentários, curtidas, imagens de postagem e
notificações**. Usuários/perfil e autenticação já saíram para o `profile_service` e o `authentication_service`.
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
- Imagens de postagem: gravadas em `src/main/resources/public/images/<id_usuario>/` relativo ao diretório de execução (volume `legacy_images` no container).
- Sem testes automatizados.

## Rotas (todas exigem `X-User-Id`, exceto a de imagem)

| Rota | Controller |
| --- | --- |
| `POST/GET /api/relacao`, `PATCH/DELETE /api/relacao/{id}`, `GET /api/relacao/compartilhado` | `RelacaoController` |
| `POST/GET /api/postagem`, `GET/DELETE /api/postagem/{id}` | `PostagemController` |
| `POST/GET /api/curtida`, `DELETE /api/curtida/{id}`, `GET /api/curtida/numero/{id_conteudo}` | `CurtidaController` |
| `GET /api/img/{id_usuario}/{id_imagem}` (pública) | `ImagemController` |
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
    │   ├── exceptions/           exceções próprias
    │   └── util/                 autenticação, configuração, imagens
    └── resources/
        ├── application.properties    VERSIONADO por engano, contém segredo antigo (jwt.secret) — não editar/commitar
        ├── log4j2.properties         log no console e em logs/app.log
        └── public/images/            imagens de postagem enviadas (servidas como estáticos do classpath)
```

## Arquivos

### Raiz do pacote `com.backend`

| Arquivo | O que representa |
| --- | --- |
| `App.java` | `main`: sobe o Javalin, abre a conexão ao iniciar o servidor, registra os services em `appData`, configura CORS, arquivos estáticos (`/public`) e todas as rotas |
| `Keys.java` | Enum de chaves tipadas do `appData` do Javalin (conexão SQL e cada service) |
| `Mensagem.java` | Resposta padrão `{ mensagem, sucesso }` |

### `controllers/`

| Arquivo | O que representa |
| --- | --- |
| `RelacaoController.java` | Seguir (aceita na hora se o perfil não é privado), buscar relação/seguidores/seguidos/pendentes, aceitar, remover e relações em comum |
| `PostagemController.java` | Criar postagem ou resposta (multipart: `legenda`, `imagem`, `id_postagem` pai), listar (feed, do usuário, com mídia, interações, respostas), buscar por id e excluir |
| `CurtidaController.java` | Curtir, buscar a curtida do usuário num conteúdo, descurtir e contar curtidas |
| `ImagemController.java` | Entrega a imagem `public/images/{id_usuario}/{id_imagem}` (sem autenticação) |
| `NotificationController.java` | Listar notificações/contar não vistas, criar notificação e marcar como vistas |

### `services/`

| Arquivo | O que representa |
| --- | --- |
| `RelacaoService.java` | Regras de relação sobre o `RelacaoDao` (criar, buscar, pendentes, seguidores, seguidos, existe, remover, aceitar) |
| `PostagemService.java` | Regras de postagem sobre o `PostagemDao` (criar, buscar por usuário/id/respostas/mídia/interações/feed, excluir) |
| `CurtidaService.java` | Regras de curtida sobre o `CurtidaDao` |
| `NotificationService.java` | Regras de notificação sobre o `NotificationDao` |
| `UsuarioService.java` | Só `buscarRelacoesEmComum` (lê a projeção `usuario`); o resto do domínio de usuário migrou |

### `dao/`

| Arquivo | O que representa |
| --- | --- |
| `RelacaoDao.java` | SQL da tabela `relacao` (com JOIN em `usuario`) |
| `PostagemDao.java` | SQL da tabela `postagem` (postagens e respostas via `id_postagem`) |
| `CurtidaDao.java` | SQL da tabela `curtida` |
| `NotificationDao.java` | SQL da tabela `notification` |
| `UsuarioDao.java` | Consulta de usuários em comum na projeção `usuario` |

### `database/`

| Arquivo | O que representa |
| --- | --- |
| `SQLRepository.java` | Cria o `HikariDataSource` (URL/usuário/senha das propriedades, até 10 conexões) — mas o `App` usa só uma conexão compartilhada |

### `model/`

| Arquivo | O que representa |
| --- | --- |
| `entities/Usuario.java` | Usuário (espelha a projeção `usuario`; ainda tem campos antigos como `senha`/`email`) |
| `entities/Relacao.java` | Relação seguidor → seguido, com `aceito` |
| `entities/Postagem.java` | Postagem: imagem, legenda, data, autor e postagem pai |
| `entities/Curtida.java` | Curtida de um usuário num conteúdo |
| `entities/Notification.java` | Notificação: remetente, destinatário, título, descrição, categoria, postagem relacionada e se foi vista |
| `enums/NotificationCategory.java` | `LIKE`, `COMENT`, `FOLLOW`, `GENERAL` (com id numérico) |

### `exceptions/`

| Arquivo | O que representa |
| --- | --- |
| `DadoInvalidoException.java` | Exceção para dados inválidos de entrada |

### `util/`

| Arquivo | O que representa |
| --- | --- |
| `AuthMiddleware.java` | `AuthValidate` (exige `X-User-Id` no modo gateway ou JWT no modo legado) e `currentUser` (usuário da requisição) |
| `AuthMode.java` | Lê `AUTH_MODE` (`gateway` ou `legacy`) |
| `PropertiesUtil.java` | Carrega `application.properties` e aplica as variáveis de ambiente (`PORTA_SERVIDOR`, `SQLDB_*`, `AUTH_MODE`, `CORS_ALLOWED_ORIGIN`…) |
| `ImagemUtil.java` | Valida (por extensão/`Content-Type`), grava com nome UUID e remove imagens de postagem |
| `JWT.java` | JWT HS256 próprio — só usado no modo `legacy` (código morto em produção) |
| `AuthCookie.java` | Define/limpa o cookie `token` e lê o Bearer do cabeçalho ou cookie — só usado no modo `legacy` |
