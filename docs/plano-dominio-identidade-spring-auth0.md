> **SUBSTITUÍDO** em 2026-11-10 por `plano-dominio-identidade-servico-proprio.md` (Auth0 descartado: tela de login própria e token
> compartilhado entre serviços). Mantido apenas como registro da alternativa avaliada.

# Plano — Migração do domínio de Identidade e Acesso (Spring Boot + Postgres + Spring Security + Auth0)

Data: 2026-11-10 · Complementa `analise-backend-e-migracao-spring-boot.md` (correções SEC-01, SEC-06, SEC-07, SEC-10).
Premissa do usuário: migração **gradual, um domínio por vez**; este é o primeiro. Nada deste plano foi implementado ainda.

> Este plano contém **suposições sobre APIs de terceiros** (Auth0, Caddy, Spring Boot/Security/Session). Versões e nomes de
> propriedades devem ser conferidos na documentação vigente no início da Fase 1 — marquei esses pontos com *(confirmar)*.

---

## 1. Escopo do domínio

**Dentro** (passa a ser do novo serviço Spring):
- Cadastro, login, logout, recuperação/troca de senha, verificação de e-mail (delegados ao Auth0).
- Sessão do navegador, proteção CSRF, redirecionamentos seguros (`returnTo`).
- Vínculo `Auth0 ⇄ usuário do sistema` (`identity_link`) e estado da conta (pendente de perfil, ativa, desativada, excluída).
- Papéis/permissões (`USER`, `ADMIN`) e a abstração `CurrentUser` usada pelos demais domínios.
- Exclusão de conta no provedor (Auth0) e evento `UserDeleted`.
- Autenticação do tráfego que **ainda** vai para o Javalin (*forward auth*, ver §3.3).
- Migração dos usuários/senhas existentes para o Auth0.

**Fora** (continuam no Javalin/MySQL até seus próprios domínios migrarem): perfil (`usuario`: nome, bio, foto, privacidade),
busca de usuários, relações, postagens, curtidas, notificações, imagens.

> Fronteira importante: **credenciais passam a viver no Auth0**; o perfil continua sendo dado de negócio. A única coluna que
> sai da tabela `usuario` por causa deste domínio é `senha` (removida só no fim, após o período de segurança).

---

## 2. Decisões de arquitetura (com a recomendação)

### 2.1 Como o navegador autentica: **BFF com sessão HttpOnly** (recomendado)

| | A. SPA guarda tokens (`auth0-spa-js`, Bearer) | **B. BFF: Spring faz o login OIDC e mantém sessão (recomendado)** |
| --- | --- | --- |
| Token no JavaScript | Sim (memória/`localStorage`) — exposto a XSS | **Não**; o navegador só tem um cookie `HttpOnly` |
| Atende a decisão anterior ("HttpOnly em prod") | Não | **Sim** |
| Refresh | Refresh token rotativo no navegador | Sessão no servidor (Spring Session JDBC) |
| CSRF | Não se aplica (Bearer) | **Necessário** (cookie XSRF + header) |
| Complexidade no backend | Baixa (*resource server*) | Média (cliente OIDC + sessão) |
| Complexidade no frontend | Média (SDK, renovação silenciosa) | **Baixa** (só redireciona) |

Escolha **B**: Spring Security como **cliente OIDC confidencial** (Authorization Code + PKCE + `client_secret`).
O Auth0 emite ID token/access token **para o servidor**; o navegador nunca os vê.

### 2.2 Mesma origem via Caddy
Servir frontend e API em **`https://app.exemplo.com`** (`/` → arquivos estáticos; `/api`, `/auth` → backends). Benefícios:
sem CORS, cookie `__Host-SESSION` (HttpOnly, Secure, Path=/, sem `Domain`, `SameSite=Lax`), CSRF simples.
O Caddy já existe no servidor.

### 2.3 Convivência com o Javalin: *forward auth* + cabeçalho confiável
Enquanto outros domínios não migram, o Javalin **não valida mais JWT**; o Caddy pergunta ao Spring se a requisição é válida e,
se for, repassa o id do usuário em `X-User-Id`. O Javalin só aceita esse cabeçalho e **deixa de ser exposto fora da rede interna**.
Efeito colateral positivo: o Javalin passa a derivar o usuário **do cabeçalho** (corrige SEC-02/IDOR domínio a domínio).

```
Navegador ──HTTPS──► Caddy ──┬── /  ─────────────────► arquivos do frontend
                             ├── /auth/*, /oauth2/*, /login/oauth2/*, /api/me, /api/onboarding, /api/account/*
                             │        └────────────────► Spring Boot (identity)  ──► Postgres (identity, sessões, outbox)
                             │                                   │  └──► Auth0 (OIDC + Management API)
                             │                                   └──► (ACL temporária) MySQL legado, somente leitura
                             └── /api/** (demais) ──forward_auth──► Spring /internal/auth/verify  (204 + X-User-Id)
                                              └──────────────────► Javalin (interno, confia em X-User-Id)
```

### 2.4 Perfil durante a transição: *Anti-Corruption Layer* mínima
`/api/me` precisa devolver o formato atual (`{id, username, nome, ...}`) enquanto o perfil está no MySQL. O módulo
`identity.legacy` expõe a porta `LegacyProfilePort` com **um adaptador temporário** (leitura/gravação mínima no MySQL por um
segundo `DataSource`). Esse pacote inteiro é apagado quando o domínio Perfil migrar. (Alternativa: o Javalin expor
endpoints internos — ver D4.)

### 2.5 Cadastro em duas etapas
Auth0 coleta **e-mail + senha** (ou login social). No primeiro acesso, se não houver perfil vinculado, `/api/me` retorna
`{"perfilPendente": true}` e o frontend leva o usuário a uma tela de **onboarding** (username, nome, telefone, sexo, data de
nascimento ≥ 18 anos) → `POST /api/onboarding`, validado no servidor. Isso evita depender de campos customizados no Universal Login.

### 2.6 Postgres
O novo serviço usa **PostgreSQL 16** (Flyway). O MySQL legado permanece só para os domínios ainda não migrados; **não há FK
entre bancos** — o vínculo é lógico (`legacy_user_id`).

---

## 3. Componentes e configuração

### 3.1 Ferramentas

| Necessidade | Ferramenta |
| --- | --- |
| Framework | Spring Boot (linha estável mais recente) *(confirmar)* + Java 21, Maven wrapper |
| Segurança | `spring-boot-starter-security`, `spring-boot-starter-oauth2-client`, `spring-security-test` |
| Sessão | `spring-session-jdbc` (tabelas no Postgres) |
| Dados | Spring Data JPA (ou Spring Data JDBC, ver D3), **Flyway**, driver PostgreSQL, Hikari (padrão) |
| Provedor | **Auth0** — Universal Login, Management API (SDK `com.auth0:auth0`), Actions, Attack Protection |
| IaC do Auth0 | **Terraform** provider `auth0/auth0` (ou Auth0 Deploy CLI) — configuração versionada, sem cliques manuais |
| Fronteiras | **Spring Modulith** (módulo `identity`, eventos, verificação de dependências) |
| Eventos/outbox | Tabela `outbox_event` + publicação por `@Scheduled`/Modulith Event Publication Registry |
| Testes | JUnit 5, **Testcontainers (Postgres)**, MockMvc + `oidcLogin()`, **mock-oauth2-server** (NAV) para fluxo OIDC, WireMock p/ Management API |
| Qualidade | Spotless/Checkstyle, **OWASP Dependency-Check**, **Gitleaks**, Dependabot/Renovate |
| Observabilidade | Actuator + Micrometer (Prometheus), logs JSON, `traceId` |
| Rate limit | Auth0 *Attack Protection* + limite no Caddy/Bucket4j nas rotas `/auth/*` |
| Operação | Docker multi-stage, `docker-compose` (caddy, identity, postgres, javalin, mysql), GitHub Actions |

### 3.2 Configuração no Auth0 (versionada em Terraform)
- **Tenants separados**: `dev`, `staging`, `prod` (região escolhida com a LGPD em mente — ver §8).
- **Application** tipo *Regular Web Application* (BFF): *Allowed Callback URLs* `https://app…/login/oauth2/code/auth0`,
  *Allowed Logout URLs* `https://app…/`, *Allowed Web Origins* `https://app…`; secret só em variável de ambiente.
- **Connection** `Username-Password-Authentication`: política de senha forte, *breached password detection*, *brute-force
  protection* e *suspicious IP throttling* ligados (resolve SEC-06 sem código próprio); e-mail verificado obrigatório
  (D6); MFA opcional.
- **Universal Login** com a identidade visual (#002bb3/#fff); provedor de e-mail próprio (SMTP/SendGrid) para verificação e
  redefinição de senha — **substitui o botão placeholder "Esqueci minha senha"**.
- **Actions**: *post-login* adiciona claims namespaced (`https://socialflux.app/roles`, `…/legacy_user_id` a partir de
  `app_metadata`); *pre-user-registration* (opcional) bloqueia domínios descartáveis.
- **Roles (RBAC)**: `USER` (padrão), `ADMIN`.
- **Machine-to-Machine** app para a Management API com **escopos mínimos** (`read:users`, `update:users`, `delete:users`,
  `create:user_tickets`, `create:users`/import jobs somente no ambiente de migração).

### 3.3 Trechos de referência

`application.yml` *(nomes de propriedade conferir na versão escolhida)*:
```yaml
spring:
  datasource:                       # Postgres (identity)
    url: ${IDENTITY_DB_URL}
    username: ${IDENTITY_DB_USER}
    password: ${IDENTITY_DB_PASSWORD}
  flyway: { enabled: true }
  session:
    store-type: jdbc
    timeout: 8h                     # ociosidade
  security:
    oauth2:
      client:
        registration:
          auth0:
            client-id: ${AUTH0_CLIENT_ID}
            client-secret: ${AUTH0_CLIENT_SECRET}
            scope: openid,profile,email
        provider:
          auth0:
            issuer-uri: https://${AUTH0_DOMAIN}/
server:
  forward-headers-strategy: framework   # atrás do Caddy (HTTPS/host corretos no redirect_uri)
  servlet.session.cookie:
    name: __Host-SESSION
    http-only: true
    secure: true
    same-site: lax
```

`Caddyfile` (esboço):
```
app.exemplo.com {
    # nunca confiar em cabeçalho vindo do cliente
    request_header -X-User-Id

    @identity path /auth/* /oauth2/* /login/oauth2/* /api/me /api/onboarding /api/account/*
    handle @identity { reverse_proxy identity:8080 }

    @internal path /internal/*
    handle @internal { respond 404 }

    handle /api/* {
        forward_auth identity:8080 {
            uri /internal/auth/verify
            copy_headers X-User-Id
        }
        reverse_proxy javalin:8000
    }

    handle { root * /srv/frontend ; try_files {path} /index.html ; file_server }
}
```
> `forward_auth` **precisa** de rede interna entre Caddy/Spring/Javalin; o Javalin não pode ter porta pública.

### 3.4 Modelo de dados (Postgres, Flyway `V1__identity.sql`)
```sql
create extension if not exists citext;

create table identity_link (
  id               uuid primary key default gen_random_uuid(),
  auth0_sub        text    not null unique,            -- ex.: auth0|legacy-123
  legacy_user_id   integer unique,                     -- usuario.id no MySQL (removido quando Perfil migrar)
  email            citext  not null,
  email_verified   boolean not null default false,
  status           text    not null default 'PENDING_PROFILE'
                   check (status in ('PENDING_PROFILE','ACTIVE','DISABLED','DELETED')),
  created_at       timestamptz not null default now(),
  last_login_at    timestamptz
);

create table outbox_event (
  id uuid primary key default gen_random_uuid(),
  type text not null,                 -- UserRegistered | UserDeleted | ...
  payload jsonb not null,
  created_at timestamptz not null default now(),
  published_at timestamptz
);

create table audit_event (            -- trilha mínima (login, onboarding, exclusão, falhas)
  id bigserial primary key,
  link_id uuid references identity_link(id) on delete set null,
  action text not null, ip inet, user_agent text, created_at timestamptz not null default now()
);
-- tabelas do Spring Session JDBC (script do pacote para PostgreSQL) em V2__spring_session.sql
```

### 3.5 Segurança (Spring Security)
- `SecurityFilterChain` única, **negar por padrão**; públicos: `/auth/login`, `/oauth2/**`, `/login/oauth2/**`, `/actuator/health`.
- Requisições `XHR` sem sessão recebem **401 JSON** (sem redirecionar); a navegação para `/auth/login` inicia o fluxo OIDC.
- `OidcUserService` customizado: no login, *upsert* em `identity_link` (por `sub`), atualiza `email_verified`/`last_login_at`,
  aplica a regra de e-mail verificado e monta `CurrentUser { linkId, userId (legacy), roles, perfilPendente }`.
- `AuthenticationSuccessHandler` redireciona para `returnTo` **validado contra *allowlist*** (evita *open redirect*).
- **CSRF**: `CookieCsrfTokenRepository` + `SpaCsrfTokenRequestHandler` (padrão recomendado para SPA); o axios envia
  `X-XSRF-TOKEN` (mesma origem simplifica).
- **Logout**: `POST /auth/logout` (com CSRF) → invalida sessão + `OidcClientInitiatedLogoutSuccessHandler` (encerra também a
  sessão no Auth0).
- **Fixação de sessão**: troca de id de sessão no login (padrão do Spring); *timeout* ocioso (ex.: 8 h) e absoluto (ex.: 7 dias).
- Cabeçalhos: HSTS, `nosniff`, `Referrer-Policy`, CSP restritiva; `Cache-Control: no-store` nas rotas autenticadas.
- `GET /internal/auth/verify`: só responde às requisições vindas do Caddy (rede interna + `@internal` bloqueado fora); devolve
  `204` + `X-User-Id` (id legado) ou `401`.

---

## 4. API do novo serviço

| Rota | Método | Descrição | Observação |
| --- | --- | --- | --- |
| `/auth/login` | GET | Inicia o login (`returnTo`, `screen_hint=signup`) | redireciona ao Auth0 |
| `/auth/logout` | POST | Encerra sessão local e no Auth0 | exige CSRF |
| `/api/me` | GET | Usuário atual no **formato legado** + `perfilPendente` | substitui o `GET /api/me` do Javalin |
| `/api/onboarding` | POST | Cria perfil (username, nome, telefone, sexo, nascimento) e vincula | Bean Validation, idade ≥ 18 |
| `/api/account/password-reset` | POST | Dispara e-mail de troca de senha (ticket Management API) | autenticado |
| `/api/account` | DELETE | Exclui a conta (reautenticação recente) → Auth0 + `UserDeleted` | exige `max_age` |
| `/internal/auth/verify` | GET | *forward auth* para o Caddy | **não público** |
| `/actuator/health` | GET | Saúde | restrito |

`/login`, `/register` e `/logout` antigos (Javalin) deixam de existir.

---

## 5. Fases de implementação

### Fase 0 — Pré-requisitos (1–2 dias) · independente de código
1. Executar a **contenção** do relatório (rotacionar `jwt.secret` e credenciais do banco, remover segredos do Git).
2. Criar os tenants Auth0 (`dev`/`staging`/`prod`) e a conta do provedor de e-mail; escolher região (LGPD).
3. Resolver as decisões D1–D8 (§7). Definir domínio único `app.exemplo.com` e HTTPS no Caddy.
4. Exportar o esquema atual do MySQL (`mysqldump --no-data`) e o inventário de usuários (quantos, e-mails duplicados/inválidos).

### Fase 1 — Esqueleto do serviço (2–3 dias)
1. Projeto Spring Boot (módulo `identity`, pacotes por domínio, Spring Modulith, Java 21, perfis `dev/staging/prod`).
2. Postgres + Flyway (`V1`, `V2`), `docker-compose` de desenvolvimento (Postgres, Caddy, Javalin+MySQL existentes, mock OIDC).
3. Testcontainers, CI (build, testes, Dependency-Check, Gitleaks), `Dockerfile` multi-stage, Actuator.
**Pronto quando:** `mvn verify` verde no CI e o serviço sobe com o Postgres.

### Fase 2 — Auth0 como código (2 dias)
Terraform do tenant (application, connection, políticas, Universal Login, Actions, roles, M2M), variáveis/segredos por ambiente,
e-mails de verificação/redefinição testados em `dev`.
**Pronto quando:** `terraform apply` recria o tenant de `dev` do zero.

### Fase 3 — Login OIDC + sessão (4–5 dias)
`SecurityFilterChain`, `oauth2Login`, `OidcUserService` (upsert `identity_link`), Spring Session JDBC, CSRF, logout OIDC,
`returnTo` seguro, 401 JSON, cabeçalhos, `GET /api/me` (via `LegacyProfilePort`), testes com `oidcLogin()` e mock-oauth2-server.
**Pronto quando:** fluxo login → `/api/me` → logout funciona ponta a ponta contra o tenant `dev`, com cookie `HttpOnly`.

### Fase 4 — Onboarding e ponte com o legado (3–4 dias)
`POST /api/onboarding` (validação server-side: username `^[a-z0-9._]+$`, idade ≥ 18, telefone, unicidade), criação do
registro de perfil no MySQL pelo ACL (senha como valor inutilizável até a coluna ser removida), vínculo em `identity_link`;
`/internal/auth/verify` + ajuste do **Javalin** (`AuthMiddleware` lê `X-User-Id`, modo `AUTH_MODE=legacy|gateway`, porta interna).
**Pronto quando:** o frontend atual funciona com sessão em `staging` e o Javalin ignora qualquer `X-User-Id` externo.

### Fase 5 — Migração dos usuários existentes (2–3 dias)
Ferramenta (CLI Spring/Java) que lê `usuario` e gera o JSON de **importação em massa** do Auth0 em lotes (respeitar o limite de
tamanho por job *(confirmar)*): `email`, `password_hash` (bcrypt `$2a$`, **mesma senha continua valendo**), `user_id`
determinístico (`legacy-<id>`), `email_verified` conforme D6, `app_metadata.legacy_user_id`. Depois popula `identity_link`
(`status=ACTIVE`). **Ensaiar em `staging`** com cópia do banco; relatório de conflitos (e-mails duplicados/inválidos), contagens
antes/depois e idempotência.
**Pronto quando:** 100 % dos usuários de `staging` entram com a senha antiga e reconciliam com o legado.

### Fase 6 — Frontend (3–4 dias)
`AuthProvider` passa a: `login()`/`register()` → `window.location.assign('/auth/login?...')`; `logout()` → `POST /auth/logout`
(CSRF); tratar 401 → login; **página de onboarding**; remover `FormSignin`/`FormSignup`/`FormField` de auth e o botão placeholder;
configurar o axios (`withCredentials`, `xsrfCookieName`/`xsrfHeaderName`; `withXSRFToken` se necessário); trocar senha → chama
`/api/account/password-reset`; excluir conta → reautenticação + `DELETE /api/account`.

### Fase 7 — Autorização como base para os próximos domínios (1–2 dias)
Abstração `CurrentUser` (id, roles) injetável; `@PreAuthorize`/*method security* habilitado; convenção "**nunca ler identidade do
corpo/URL**"; evento `UserDeleted` (outbox) documentado para o domínio Perfil consumir; testes negativos de IDOR para o fluxo de conta.

### Fase 8 — Endurecimento e operação (2 dias)
Limites de taxa em `/auth/*`, *Attack Protection* verificado, CSP/HSTS, alertas (taxa de falhas de login, 5xx, expiração de
secrets), *runbook*, backup/restore do Postgres, rotação de `client_secret`, política de logs sem PII.

### Fase 9 — Cutover e plano de retorno (2 dias)
1. `staging`: ensaio completo (migração + testes manuais + Playwright nos fluxos críticos).
2. Janela curta: congelar novos cadastros → importar delta de usuários → publicar `identity` + frontend → Caddy passa
   `/auth/*`, `/api/me`, etc. para o Spring e liga `forward_auth` → Javalin em `AUTH_MODE=gateway`.
3. Cookies/JWT antigos deixam de valer (esperado: todos precisam entrar de novo).
4. **Retorno**: reverter as rotas do Caddy e `AUTH_MODE=legacy` (o código JWT do Javalin fica **uma versão a mais** antes de ser removido).
5. Após estabilizar: remover rotas/JWT de auth do Javalin e, depois do período de segurança, a coluna `usuario.senha`.

**Estimativa total:** ~20–25 dias úteis para uma pessoa (3–4 semanas corridas).

---

## 6. Estratégia de testes

| Camada | O que cobrir |
| --- | --- |
| Unidade | regras de onboarding, mapeamento de claims, `returnTo` seguro |
| Slice/MockMvc | `oidcLogin()`, 401 JSON, CSRF obrigatório em `POST/DELETE`, `/internal/auth/verify` |
| Integração | Postgres (Testcontainers), `mock-oauth2-server` para o fluxo OIDC real, WireMock para a Management API |
| Contrato | `/api/me` idêntico ao formato atual do frontend |
| Segurança | open redirect, fixação de sessão, forja de `X-User-Id`, acesso a `/internal/*` pela internet, CSRF, logout |
| E2E (staging) | Playwright: cadastro → verificação → onboarding → feed; login com senha migrada; logout; exclusão |
| Migração | contagens, amostra de logins, conflitos, reexecução idempotente |

---

## 7. Decisões que preciso do seu parecer

| # | Decisão | Opções (recomendada em **negrito**) |
| --- | --- | --- |
| D1 | Modelo de sessão | **BFF com sessão HttpOnly** · SPA com tokens (`auth0-spa-js`) |
| D2 | Origem | **Mesma origem (`app.exemplo.com`) via Caddy** · domínios separados com CORS |
| D3 | Acesso a dados do `identity` | **Spring Data JPA** · Spring Data JDBC/`JdbcTemplate` |
| D4 | Ponte com o perfil legado | **ACL com segundo `DataSource` (apagada depois)** · endpoints internos no Javalin |
| D5 | Cadastro | **Auth0 (e-mail+senha/social) + onboarding no app** · formulário próprio criando o usuário via Management API |
| D6 | E-mails já existentes | Importar `email_verified=false` e **exigir verificação** · importar como verificado (risco: e-mails nunca foram validados) |
| D7 | Login social (Google etc.) | **Não no v1** · incluir já |
| D8 | Tempo de sessão | **8 h ociosa / 7 dias absoluta** · outro |
| D9 | Excluir conta | **Reautenticação recente + exclusão no Auth0 + evento** · só soft-delete |
| D10 | Plano do Auth0 | Confirmar limites/preço (MAU, domínio customizado, MFA, SMTP) antes de comprometer o desenho |

---

## 8. Riscos e pontos de atenção

- **Dependência de fornecedor / custo**: Auth0 cobra por MAU e recursos (domínio customizado, MFA, *attack protection* avançado
  variam por plano) — validar o plano (D10). Alternativas self-hosted equivalentes: Keycloak, Zitadel, Spring Authorization Server.
- **Dois bancos temporariamente** (MySQL + Postgres) e uma ponte (ACL) que precisa ser removida — risco de virar permanente.
  Mitigar com data de remoção atrelada à migração do domínio Perfil.
- **Falsificação de `X-User-Id`** se o Javalin ficar acessível fora da rede interna ou se o Caddy não limpar o cabeçalho.
- **Open redirect / CSRF** no fluxo de login: `returnTo` com *allowlist* e token XSRF.
- **Migração de senhas**: confirmar formato dos hashes (`$2a$`, custo) e limites do *import job*; e-mails duplicados/inválidos.
- **Limites de taxa** do Auth0 (login e Management API) — evitar chamadas por requisição; cachear.
- **LGPD**: Auth0 passa a tratar dados pessoais (e-mail, nome). Definir região do tenant, DPA/contrato, base legal, política de
  retenção/exclusão; garantir que a exclusão de conta remova também os dados no provedor.
- **Disponibilidade**: login depende do Auth0 (a sessão local mantém usuários já logados durante indisponibilidade breve).
- **Sessão no Postgres** adiciona carga leve; monitorar tamanho/limpeza das tabelas de sessão.
- **Spring Boot 4 / versões**: nomes de propriedades e *starters* podem mudar; fixar a versão na Fase 1 e testar o fluxo OIDC primeiro.
