# Rodando as aplicações na sua máquina (só os bancos em containers)

## Caminho rápido (scripts que leem o seu `.env`)

Depois de subir a infraestrutura (passo 1 abaixo), em **um terminal por serviço, na raiz do repositório**:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/run-auth.ps1      # authentication_service (8081)
powershell -ExecutionPolicy Bypass -File scripts/dev/run-profile.ps1   # profile_service (8082)
powershell -ExecutionPolicy Bypass -File scripts/dev/run-graph.ps1     # social_graph_service (8083)
powershell -ExecutionPolicy Bypass -File scripts/dev/run-post.ps1      # post_service (8084)
powershell -ExecutionPolicy Bypass -File scripts/dev/run-notification.ps1   # notification_service (8085)
```

(bash: `./scripts/dev/run-auth.sh`, `run-profile.sh`, `run-graph.sh`, `run-post.sh` e `run-notification.sh`.) E em `FrontEnd/`: `npm ci && npm run dev`.

Os scripts usam **o banco, o usuário e a senha do seu `.env`** — os mesmos dos containers. Os dois erros mais comuns ao rodar
na mão vêm de usar valores diferentes dos do `.env`:

| Erro | Causa |
| --- | --- |
| auth service: `database "identity" does not exist` / `password authentication failed` | o `.env` define outro `IDENTITY_DB_NAME`/`IDENTITY_DB_USER` (ex.: `social_flux`/`root`) e a app usou os padrões `identity`/`identity` |
| auth service: porta 8081 em uso | outra instância do serviço ainda rodando |

Para ver quem usa a porta: `Get-NetTCPConnection -LocalPort 8081 -State Listen` (PowerShell).

## 1. Infraestrutura

Docker Desktop ligado e, na raiz do repositório:

```bash
cp .env.example .env        # ajuste IDENTITY_DB_PASSWORD, RABBITMQ_PASSWORD e as chaves do RustFS
docker compose --profile host up -d
docker compose ps           # o postgres deve ficar "healthy"
```

Sobe: PostgreSQL (`localhost:5432`; o serviço `profile_db_init` cria o banco `profile` ao lado do banco do auth, com as mesmas credenciais), **RabbitMQ** (`localhost:5672`, painel http://localhost:15672), **RustFS** (armazenamento de mídia S3 em `localhost:9000`, só no loopback; o serviço `rustfs_init` cria os buckets; exige `RUSTFS_ACCESS_KEY` e `RUSTFS_SECRET_KEY` no `.env`) e Mailpit
(SMTP `localhost:1025`, caixa de entrada http://localhost:8025) e o **gateway** Caddy em `localhost:8080`, que encaminha
`/auth` → `localhost:8081`, `/api/usuarios*` e `/media/avatars` → `localhost:8082` (perfil), `/api/relacoes*` → `localhost:8083` (grafo), `/api/postagens*` e `/media/posts/*` → `localhost:8084` (postagens), `/api/notificacoes*` → `localhost:8085` (notificações e o stream SSE), o resto de `/api` → 404 e o resto → Vite `localhost:5173`.

Se já houver um Postgres na sua máquina na mesma porta, mude `POSTGRES_PORT` no `.env` e use essas portas abaixo.

> O gateway é necessário: ele converte o cookie de sessão em `Authorization: Bearer` e confere a revogação do token (`forward_auth`) antes de encaminhar para cada serviço.

## 2. authentication_service (porta 8081)

> Os serviços dependem da biblioteca `libs/socialflux-events`. Os scripts de `scripts/dev/` já a instalam; rodando à mão,
> instale uma vez (e depois de mudar a biblioteca), na raiz: `mvn -q -pl libs/socialflux-events -DskipTests install`.

Use as **mesmas** senhas do `.env`. Em um terminal, em `authentication_service/`:

**bash (Git Bash / Linux / macOS)**
```bash
export IDENTITY_DB_URL=jdbc:postgresql://localhost:5432/identity
export IDENTITY_DB_USER=identity
export IDENTITY_DB_PASSWORD='a-mesma-do-.env'
export AUTH_ALLOW_EPHEMERAL_KEY=true          # chave RSA em memória (sessões caem a cada reinício)
export AUTH_COOKIE_SECURE=false AUTH_COOKIE_ACCESS_NAME=at AUTH_COOKIE_REFRESH_NAME=rt
export AUTH_PUBLIC_BASE_URL=http://localhost:5173
export AUTH_MAIL_ENABLED=true SMTP_HOST=localhost SMTP_PORT=1025
export PROFILE_SERVICE_URL=http://localhost:8082
export RABBITMQ_HOST=localhost RABBITMQ_USER=socialflux RABBITMQ_PASSWORD='a-mesma-do-.env'
mvn spring-boot:run
```

**PowerShell**
```powershell
$env:IDENTITY_DB_URL='jdbc:postgresql://localhost:5432/identity'
$env:IDENTITY_DB_USER='identity'
$env:IDENTITY_DB_PASSWORD='a-mesma-do-.env'
$env:AUTH_ALLOW_EPHEMERAL_KEY='true'
$env:AUTH_COOKIE_SECURE='false'; $env:AUTH_COOKIE_ACCESS_NAME='at'; $env:AUTH_COOKIE_REFRESH_NAME='rt'
$env:AUTH_PUBLIC_BASE_URL='http://localhost:5173'
$env:AUTH_MAIL_ENABLED='true'; $env:SMTP_HOST='localhost'; $env:SMTP_PORT='1025'
$env:PROFILE_SERVICE_URL='http://localhost:8082'
$env:RABBITMQ_HOST='localhost'; $env:RABBITMQ_USER='socialflux'; $env:RABBITMQ_PASSWORD='a-mesma-do-.env'
mvn spring-boot:run
```

Pronto quando aparecer `Started AuthenticationServiceApplication`. O Flyway cria as tabelas no Postgres.
Para uma chave fixa: `./scripts/generate-jwt-key.sh` e use `JWT_PRIVATE_KEY_B64`/`JWT_KEY_ID` no lugar da chave efêmera.

## 2b. profile_service (porta 8082)

Use `scripts/dev/run-profile.ps1` (ou `.sh`): ele usa o banco `profile` na mesma instância PostgreSQL, **com o mesmo usuário e senha**
do `IDENTITY_DB_*` do `.env`, valida o JWT pelo JWKS do auth (`http://localhost:8081/.well-known/jwks.json`, **o auth precisa estar
no ar**). Pronto quando aparecer `Started ProfileServiceApplication`.
As fotos de perfil vão para o bucket `avatars` do RustFS (`http://localhost:9000`, com `RUSTFS_ACCESS_KEY`/`RUSTFS_SECRET_KEY` do `.env`): suba a infra antes (`docker compose --profile host up -d`).

## 2c. social_graph_service (porta 8083)

Use `scripts/dev/run-graph.ps1` (ou `.sh`): banco `social_graph` na mesma instância PostgreSQL, **com o mesmo usuário e senha** do
`IDENTITY_DB_*`, valida o JWT pelo JWKS do auth e publica `follow.*` (o `post_service` e o `notification_service` consomem). O banco é criado
pelo serviço `graph_db_init` do compose (suba a infra antes). Pronto quando aparecer `Started GraphServiceApplication`. O gateway já
roteia `/api/relacoes*` para ele (porta 8083 no host); o frontend já o usa (veja o README dele).

## 2d. post_service (porta 8084)

Use `scripts/dev/run-post.ps1` (ou `.sh`): banco `post` na mesma instância PostgreSQL, **com o mesmo usuário e senha** do
`IDENTITY_DB_*` (criado pelo `post_db_init` do compose), valida o JWT pelo JWKS do auth, consome `profile.*` e `follow.*` do
RabbitMQ e grava as imagens no bucket `post-media` do RustFS (`http://localhost:9000`, `RUSTFS_ACCESS_KEY`/`RUSTFS_SECRET_KEY`
do `.env`). Pronto quando aparecer `Started PostServiceApplication`. O gateway roteia `/api/postagens*` e `/media/posts/*` para ele.
Ele só conhece usuários e relações criados **depois** de subir (sem bootstrap): em desenvolvimento, crie contas novas ou recrie os volumes.

## 2e. notification_service (porta 8085)

Use `scripts/dev/run-notification.ps1` (ou `.sh`): banco `notification` na mesma instância PostgreSQL, **com o mesmo usuário e senha**
do `IDENTITY_DB_*` (criado pelo `notification_db_init` do compose), valida o JWT pelo JWKS do auth e consome `profile.*`, `follow.*` e
`post.*` do RabbitMQ. Não há rota para criar notificação: elas nascem dos eventos. Pronto quando aparecer `Started NotificationServiceApplication`.
O gateway roteia `/api/notificacoes*` (inclusive o stream SSE `/api/notificacoes/stream`) para ele. Como os outros serviços, só conhece
usuários e relações criados **depois** de subir (sem bootstrap). Para ver o tempo real sem o frontend:
`curl -N --cookie "at=<token>" http://localhost:8080/api/notificacoes/stream` (o cookie `at` do navegador logado).

## 3. Frontend (porta 5173)

Em `FrontEnd/`:

```bash
npm ci
npm run dev
```

Abra **http://localhost:5173**. O Vite encaminha `/auth`, `/api` e `/media` para o gateway (`localhost:8080`).

## Testando

1. Cadastre-se pela tela. Você entra logado.
2. Abra http://localhost:8025 e clique no link de confirmação de e-mail.
3. Teste login/logout, "Esqueci minha senha" (link também no Mailpit), Configurações → alterar senha / sair de todos os dispositivos.
4. Teste o perfil (editar nome/bio/privado, trocar foto, pesquisar usuários) postagens (texto, imagem, GIF, comentar, curtir, excluir, feed, abas do perfil, conta privada), seguir (use duas contas em janelas diferentes) e as notificações (curtir, comentar e seguir chegam em tempo real na outra janela).
5. Sessão deslizante: reinicie o serviço com `JWT_ACCESS_TTL=PT2M JWT_RENEW_WINDOW=PT1M JWT_REFRESH_GRACE=PT2M`, entre,
   espere mais de 1 minuto e faça uma ação: aparece `POST /auth/refresh` (200) na aba Network.

## Dados de teste: seguidores e seguidos

Com tudo no ar (infra, auth, profile, graph, e post/notification se quiser que também conheçam as contas), crie contas de teste e
relações com a sua conta (30 de cada por padrão, suficiente para testar "Carregar mais"):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/seed-relacoes.ps1 -Email seu@email.com
```

O script pede a sua senha no prompt (não vai para o histórico nem para o disco). Cria `mock_seguidor_NN` (seguem você) e
`mock_seguido_NN` (você segue) pelo gateway, com senhas aleatórias descartadas. Rodar de novo falha nas contas que já existem.
Opções: `-Quantidade 10`, `-Gateway http://localhost:8080`.

## Problemas comuns

| Sintoma | Causa provável |
| --- | --- |
| `Connection refused` no Postgres | containers ainda iniciando (`docker compose ps`) ou portas diferentes no `.env` |
| `/api/...` devolve 401/502 | o `authentication_service` (ou o serviço da rota) está fora do ar |
| Cadastro dá 503 `profile_unavailable` | `profile_service` fora do ar (ou `PROFILE_SERVICE_URL` errado) |
| Cadastro funciona, mas postar dá 503 `perfil_indisponivel` | o `post_service` ainda não recebeu o `profile.created.v1` da conta: confira se o RabbitMQ está no ar e a fila `post-service.profile-events` no painel (http://localhost:15672). Contas criadas antes de o `post_service` subir não são conhecidas por ele (sem bootstrap): crie outra conta ou recrie os volumes |
| Notificação não chega (tempo real ou lista) | o `notification_service` ainda não recebeu os eventos: confira se ele está no ar, o RabbitMQ e as filas `notification-service.{profile,follow,post}-events` no painel (http://localhost:15672). Contas e relações criadas antes de ele subir não são conhecidas (sem bootstrap). O stream SSE para de chegar se um proxy bufferiza ou comprime `/api/notificacoes/stream` |
| Perfil novo "invisível" para os outros | o evento `account.registered.v1` ainda não chegou ao profile_service (broker fora do ar?): fica pendente no outbox e é entregue quando o broker volta |
| Sessão cai a cada reinício do serviço | chave RSA efêmera; configure `JWT_PRIVATE_KEY_B64` |
| `host.docker.internal` não resolve (Linux) | já há `extra_hosts` no compose; confira a versão do Docker (≥ 20.10) |
