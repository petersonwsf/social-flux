# Rodando as aplicações na sua máquina (só os bancos em containers)

## Caminho rápido (scripts que leem o seu `.env`)

Depois de subir a infraestrutura (passo 1 abaixo), em **três terminais na raiz do repositório**:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/run-auth.ps1      # authentication_service (8081)
powershell -ExecutionPolicy Bypass -File scripts/dev/run-profile.ps1   # profile_service (8082)
powershell -ExecutionPolicy Bypass -File scripts/dev/run-legacy.ps1    # backend legado (8000)
```

(bash: `./scripts/dev/run-auth.sh`, `./scripts/dev/run-profile.sh` e `./scripts/dev/run-legacy.sh`.) E em `FrontEnd/`: `npm ci && npm run dev`.

Os scripts usam **o banco, o usuário e a senha do seu `.env`** — os mesmos dos containers. Os dois erros mais comuns ao rodar
na mão vêm de usar valores diferentes dos do `.env`:

| Erro | Causa |
| --- | --- |
| auth service: `database "identity" does not exist` / `password authentication failed` | o `.env` define outro `IDENTITY_DB_NAME`/`IDENTITY_DB_USER` (ex.: `social_flux`/`root`) e a app usou os padrões `identity`/`identity` |
| backend legado: `Access denied for user 'root'` | rodou sem as variáveis: usou o `BackEnd/src/main/resources/application.properties` antigo (usuário `root` e a senha antiga) e sem `AUTH_MODE=gateway` |
| backend legado: `Port already in use ... 8000` | já há outro processo na 8000 (outra execução do legado, ou o container `legacy_backend` do perfil `apps`) |
| auth service: porta 8081 em uso | outra instância do serviço ainda rodando |

Para ver quem usa a porta: `Get-NetTCPConnection -LocalPort 8000 -State Listen` (PowerShell).

## 1. Infraestrutura

Docker Desktop ligado e, na raiz do repositório:

```bash
cp .env.example .env        # ajuste IDENTITY_DB_PASSWORD, LEGACY_DB_ROOT_PASSWORD e LEGACY_DB_PASSWORD
docker compose --profile host up -d
docker compose ps           # postgres e mysql devem ficar "healthy"
```

Sobe: PostgreSQL (`localhost:5432`; o serviço `profile_db_init` cria o banco `profile` ao lado do banco do auth, com as mesmas credenciais), MySQL
legado (`localhost:3306`), **RabbitMQ** (`localhost:5672`, painel http://localhost:15672) e Mailpit
(SMTP `localhost:1025`, caixa de entrada http://localhost:8025) e o **gateway** Caddy em `localhost:8080`, que encaminha
`/auth` → `localhost:8081`, `/api/usuarios*` e `/media/avatars` → `localhost:8082` (perfil), o resto de `/api` → `localhost:8000` (depois de validar o token) e o resto → Vite `localhost:5173`.

Se já houver Postgres/MySQL na sua máquina nas mesmas portas, mude `POSTGRES_PORT`/`MYSQL_PORT` no `.env` e use essas portas abaixo.

> O gateway é necessário: ele valida o JWT (cookie → Bearer, `forward_auth`) e entrega `X-User-Id` ao backend legado.

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
no ar**) e mantém a tabela `usuario` do MySQL atualizada (projeção). Pronto quando aparecer `Started ProfileServiceApplication`.
As fotos de perfil são gravadas em `profile_service/data/avatars/` (ignorado pelo Git).

## 2c. social_graph_service (porta 8083)

Use `scripts/dev/run-graph.ps1` (ou `.sh`): banco `social_graph` na mesma instância PostgreSQL, **com o mesmo usuário e senha** do
`IDENTITY_DB_*`, valida o JWT pelo JWKS do auth e mantém a tabela `relacao` do MySQL atualizada (projeção). O banco ainda não é
criado pelo `docker-compose.yml`; crie uma vez: `docker compose exec postgres psql -U identity -d identity -c "CREATE DATABASE social_graph"`.
Pronto quando aparecer `Started GraphServiceApplication`. **O gateway e o frontend ainda não usam este serviço** (veja o README dele).

## 3. Backend legado (porta 8000), em modo gateway

Em outro terminal, em `BackEnd/`:

**bash**
```bash
export AUTH_MODE=gateway
export PORTA_SERVIDOR=8000
export SQLDB_URL=jdbc:mysql://localhost:3306/social_flux
export SQLDB_USERNAME=social
export SQLDB_PASSWORD='a-mesma-do-.env'
mvn compile exec:java
```

**PowerShell**
```powershell
$env:AUTH_MODE='gateway'; $env:PORTA_SERVIDOR='8000'
$env:SQLDB_URL='jdbc:mysql://localhost:3306/social_flux'
$env:SQLDB_USERNAME='social'; $env:SQLDB_PASSWORD='a-mesma-do-.env'
mvn compile exec:java
```

As variáveis de ambiente sobrescrevem o `application.properties` local (que pode ter dados antigos). As imagens enviadas
são gravadas em `BackEnd/src/main/resources/public/images/`.

## 4. Frontend (porta 5173)

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
4. Teste o perfil (editar nome/bio/privado, trocar foto, pesquisar usuários) e as funções do legado: criar postagem, curtir, seguir (use duas contas em janelas diferentes), notificações.
5. Sessão deslizante: reinicie o serviço com `JWT_ACCESS_TTL=PT2M JWT_RENEW_WINDOW=PT1M JWT_REFRESH_GRACE=PT2M`, entre,
   espere mais de 1 minuto e faça uma ação: aparece `POST /auth/refresh` (200) na aba Network.

## Problemas comuns

| Sintoma | Causa provável |
| --- | --- |
| `Connection refused` no Postgres/MySQL | containers ainda iniciando (`docker compose ps`) ou portas diferentes no `.env` |
| `Access denied` no MySQL | senha/usuário diferentes de `LEGACY_DB_USER`/`LEGACY_DB_PASSWORD`. O MySQL só lê o `.env` ao criar o volume: depois de mudar a senha, `docker compose down -v` e suba de novo |
| `/api/...` devolve 401/502 | backend legado ou auth service fora do ar, ou `AUTH_MODE` não está `gateway` |
| Cadastro dá 503 `profile_unavailable` | `profile_service` fora do ar (ou `PROFILE_SERVICE_URL` errado) |
| Cadastro funciona, mas postar dá erro / perfil não aparece nas postagens | a projeção `usuario` no MySQL atrasou ou falhou: confira se o RabbitMQ está no ar, se o `profile_service` roda com `PROFILE_LEGACY_ENABLED=true` e a fila `profile-service.legacy-projection` no painel (http://localhost:15672). Volume MySQL antigo: recrie (ver README) |
| Perfil novo "invisível" para os outros | o evento `account.registered.v1` ainda não chegou ao profile_service (broker fora do ar?): fica pendente no outbox e é entregue quando o broker volta |
| Sessão cai a cada reinício do serviço | chave RSA efêmera; configure `JWT_PRIVATE_KEY_B64` |
| `host.docker.internal` não resolve (Linux) | já há `extra_hosts` no compose; confira a versão do Docker (≥ 20.10) |
