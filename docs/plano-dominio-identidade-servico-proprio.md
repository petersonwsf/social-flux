# Plano — Domínio de Identidade e Acesso: serviço próprio com JWT (Spring Boot + Postgres + Spring Security)

Data: 2026-11-10 · **Substitui** `plano-dominio-identidade-spring-auth0.md` (Auth0 descartado: a tela de login continua no app e o
token é compartilhado entre os serviços). Complementa `analise-backend-e-migracao-spring-boot.md` (SEC-01, SEC-06, SEC-07, SEC-10).

Nada deste plano foi implementado. Pontos que dependem de versões/documentação de terceiros estão marcados *(confirmar)*.

## 0. Decisões já tomadas

| Decisão | Valor |
| --- | --- |
| Assinatura | **RS256** (chave privada só no serviço de identidade; demais serviços usam a chave pública via JWKS) |
| `sub` | **UUID da conta** (recomendo UUIDv7) — é o id do usuário em todo o sistema |
| Validade do token de acesso | **2 horas**, com **renovação automática se o usuário estiver ativo na hora da expiração** |
| Segredos/chaves | **Variáveis de ambiente** |
| Tela de login | **Continua no frontend** (formulário próprio) |
| Sessão no navegador | Cookies `HttpOnly` (decisão anterior), frontend e API na mesma origem via Caddy |
| Banco | PostgreSQL (novo); MySQL legado só para domínios ainda não migrados |

---

## 1. Escopo do domínio

**Dentro** do serviço `identity`:
- Cadastro, login, logout, "sair de todos os dispositivos", renovação de sessão.
- Verificação de e-mail, esqueci/redefinir senha, troca de senha, exclusão de conta (com senha).
- Emissão de JWT RS256, publicação do JWKS, rotação de chaves.
- Limite de tentativas, trilha de auditoria, papéis (`USER`, `ADMIN`).
- Endpoint de verificação para o gateway (`/internal/auth/verify`) enquanto o Javalin existir.
- Importação dos usuários e hashes de senha atuais.

**Fora** (outros domínios, migrados depois): perfil, busca, relações, postagens, curtidas, notificações, imagens.
Conta e perfil são **1:1 e compartilham o mesmo UUID**; hoje o perfil ainda vive no MySQL com `id` inteiro.

---

## 2. Arquitetura

```
Navegador ──HTTPS──► Caddy ──┬── /                      ► frontend estático
   cookies HttpOnly          ├── /auth/*, /.well-known/jwks.json ► identity (Spring) ──► Postgres
                             │        (login, refresh, logout, me, reset, verify…)         └─► SMTP
                             ├── /api/** (legado) ─ forward_auth ─► identity /internal/auth/verify
                             │                          └─ 204 + X-User-Id (id inteiro legado)
                             │                     └──────────────► Javalin (rede interna, confia em X-User-Id)
                             └── /api/** (domínios novos) ─► serviço Spring (resource server: valida JWT via JWKS)
```

### 2.1 Cookies no navegador, Bearer entre serviços
- O Caddy **converte o cookie em `Authorization: Bearer`** antes de repassar às APIs
  (`request_header Authorization "Bearer {http.request.cookie.__Host-at}"` *(confirmar sintaxe)*). Assim os serviços internos são
  *resource servers* puros (só Bearer): **não tratam cookie nem CSRF**, e chamadas serviço→serviço usam o mesmo header.
- Cookies:
  - `__Host-at` — access token (JWT). `HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=7200`.
  - `__Secure-rt` — refresh token opaco. `HttpOnly; Secure; SameSite=Strict; Path=/auth` (só viaja para `/auth/*`).
    (O prefixo `__Host-` exige `Path=/`, por isso o refresh usa `__Secure-`.)
- **CSRF**: `SameSite` + checagem de `Origin`/`Sec-Fetch-Site` em métodos não seguros (`POST/PUT/PATCH/DELETE`) no gateway e,
  em `/auth/*`, token duplo-cookie (`XSRF-TOKEN` legível + header `X-XSRF-TOKEN`). Detalhar na Fase 5.

### 2.2 Estrutura do JWT de acesso
```json
header : { "alg": "RS256", "typ": "JWT", "kid": "2026-11-a" }
payload: { "iss": "https://app.exemplo.com", "aud": ["socialflux-api"],
           "sub": "<uuid da conta>", "sid": "<uuid da sessão/família>",
           "iat": 0, "nbf": 0, "exp": 0 /* iat + 2h */, "jti": "<uuid>",
           "roles": ["USER"], "ver": 3,
           "legacy_uid": 123 /* TEMPORÁRIA: id inteiro do MySQL, removida quando Perfil migrar */ }
```
Sem e-mail, nome ou telefone. `ver` = `token_version` da conta (revogação, §2.4). *Leeway* de relógio de ~60 s na validação.

### 2.3 Renovação automática ("se estiver ativo na hora da expiração")
Definição operacional, configurável:

| Parâmetro | Padrão | Significado |
| --- | --- | --- |
| `JWT_ACCESS_TTL` | `PT2H` | validade do access token |
| `JWT_RENEW_WINDOW` | `PT10M` | a partir de `exp − janela`, **uma requisição do usuário dispara a renovação** |
| `JWT_REFRESH_GRACE` | `PT5M` | tolerância **após** `exp` em que ainda dá para renovar (cobre a requisição exatamente na expiração) |
| `SESSION_ABSOLUTE_MAX` | `P7D` | tempo máximo de uma sessão mesmo com atividade; depois exige novo login |

Regra resultante: o usuário continua logado **se fizer ao menos uma ação entre `exp − 10 min` e `exp + 5 min`**. Se ficar ocioso
além disso, a próxima ação cai no login. O **refresh token** de cada rotação vale `access TTL + grace` (≈ 2h05) — é isso que
materializa "ativo na expiração".

Como o frontend sabe quando renovar, se o cookie é `HttpOnly` (JS não lê o `exp`)? As respostas de `/auth/login`, `/auth/refresh`
e `/auth/me` trazem `expiresAt` (não secreto), guardado **em memória** no `AuthProvider`. O interceptor do axios:
1. antes de cada requisição, se `agora ≥ expiresAt − janela` → chama `/auth/refresh` (**uma única vez**, ver abaixo);
2. se receber `401` por expiração → tenta `/auth/refresh` **uma vez** e repete a requisição; se falhar → tela de login.

**Várias abas / requisições simultâneas:** serializar com *single-flight* + **Web Locks API** (`navigator.locks`), e o servidor
tolera reuso do refresh token anterior por ~10 s para evitar falso positivo da detecção de roubo.

### 2.4 Refresh token (opaco, rotativo) e revogação
- Refresh = 256 bits aleatórios (base64url); no banco só o **hash SHA-256**. Cada uso **gira** o token (invalida o anterior) dentro
  da mesma **família** (`sid`). **Reuso de um token já girado fora da tolerância ⇒ revoga a família inteira** + auditoria.
- **Logout**: revoga a família atual e limpa os cookies. **Sair de todos / trocar senha / banir / excluir**: incrementa
  `account.token_version` e revoga todas as famílias.
- **Com TTL de 2 h, `token_version` deve estar na v1** (com 15 min eu recomendava adiar; com 2 h a janela de abuso é grande
  demais). Aplicação: o gateway (`/internal/auth/verify`) confere `ver` do token contra um *mapa em memória* só das contas cujo
  `token_version` mudou nas últimas 2h05 (reconstruído do banco ao iniciar). Barato, sem consulta por requisição e sem custo para
  os demais serviços (validam assinatura localmente — defesa em profundidade).

### 2.5 Segredos e chaves em variáveis de ambiente
```
JWT_PRIVATE_KEY_B64   # PEM PKCS#8 da chave RSA atual, em base64 de uma linha
JWT_KEY_ID            # kid atual, ex.: 2026-11-a
JWT_PUBLIC_KEYS_B64   # (opcional) chaves públicas ANTERIORES, separadas por vírgula, para o JWKS durante a rotação
JWT_ISSUER, JWT_AUDIENCE
JWT_ACCESS_TTL, JWT_RENEW_WINDOW, JWT_REFRESH_GRACE, SESSION_ABSOLUTE_MAX
IDENTITY_DB_URL, IDENTITY_DB_USER, IDENTITY_DB_PASSWORD
SMTP_HOST, SMTP_USER, SMTP_PASSWORD, MAIL_FROM
```
- Gerar (RSA ≥ 2048; sugiro 3072): `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out private.pem`
  e `base64 -w0 private.pem`. A chave pública é derivada em memória; o JWKS é gerado a partir dela.
- **Falha rápida**: a aplicação não sobe se faltar/for inválida alguma variável (`@ConfigurationProperties` + validação).
- **Rotação sem derrubar sessões** (3 passos): (1) publicar a nova chave **pública** no JWKS, ainda assinando com a antiga e
  esperar o cache dos consumidores (~10 min); (2) trocar a assinatura para a nova (`JWT_PRIVATE_KEY_B64`/`JWT_KEY_ID`),
  mantendo a antiga em `JWT_PUBLIC_KEYS_B64`; (3) após `2h + grace + leeway`, remover a antiga.
- **Cuidados inerentes a variáveis de ambiente** (aceitas por decisão): aparecem em `docker inspect`/`/proc`; exigem reinício
  para trocar; podem vazar em logs. Mitigar: `.env` fora do Git (`.gitignore` + `gitleaks`), permissão `600`, desabilitar
  `management.endpoint.env`/`configprops` do Actuator, nunca logar propriedades, usar *secrets* do CI. Opcional futuro: ler
  de arquivo (`*_FILE`/Docker secrets) mantendo a mesma interface de configuração.

### 2.6 Hash de senha
Importar os hashes atuais (`$2a$`, jbcrypt) como `{bcrypt}…` no `DelegatingPasswordEncoder` — **ninguém precisa redefinir senha**.
Padrão novo: **Argon2id** (parâmetros OWASP vigentes *(confirmar)*; requer BouncyCastle) com `upgradeEncoding`: no próximo
login bem-sucedido o hash é regravado em Argon2id.

---

## 3. Modelo de dados (Postgres, Flyway)

```sql
create extension if not exists citext;

create table account (
  id               uuid primary key,                       -- UUIDv7 gerado pela aplicação; é o `sub`
  email            citext not null unique,
  password_hash    text   not null,                        -- {argon2}… | {bcrypt}…
  email_verified   boolean not null default false,
  status           text not null default 'ACTIVE' check (status in ('ACTIVE','DISABLED','DELETED')),
  token_version    integer not null default 1,
  token_version_changed_at timestamptz,
  failed_logins    integer not null default 0,
  last_login_at    timestamptz,
  legacy_user_id   integer unique,                         -- TEMPORÁRIO (MySQL)
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

create table account_role (account_id uuid references account(id) on delete cascade, role text not null, primary key (account_id, role));

create table refresh_token (
  id uuid primary key,
  family_id uuid not null,                                 -- = `sid` do JWT
  account_id uuid not null references account(id) on delete cascade,
  token_hash bytea not null unique,
  issued_at timestamptz not null, expires_at timestamptz not null,
  family_expires_at timestamptz not null,                  -- limite absoluto da sessão
  rotated_at timestamptz, replaced_by uuid, revoked_at timestamptz, revoke_reason text,
  ip inet, user_agent text
);
create index on refresh_token (account_id); create index on refresh_token (family_id);

create table email_token (                                  -- verificação e redefinição (uso único)
  id uuid primary key, account_id uuid not null references account(id) on delete cascade,
  purpose text not null check (purpose in ('VERIFY_EMAIL','RESET_PASSWORD')),
  token_hash bytea not null unique, expires_at timestamptz not null, used_at timestamptz
);

create table audit_event (
  id bigserial primary key, account_id uuid, action text not null,
  ip inet, user_agent text, detail jsonb, created_at timestamptz not null default now()
);

create table outbox_event (id uuid primary key, type text not null, payload jsonb not null,
  created_at timestamptz not null default now(), published_at timestamptz);   -- UserRegistered, UserDeleted…
```
Limpeza periódica (`@Scheduled`) de `refresh_token`/`email_token` vencidos.

---

## 4. API

| Rota | Método | Descrição |
| --- | --- | --- |
| `/auth/register` | POST | Mantém os campos atuais (email, senha, username, nome, telefone, sexo, nascimento). Valida no servidor (senha ≥ 8 + checagem de senha vazada, idade ≥ 18, username `^[a-z0-9._]+$`), cria conta + perfil legado (ACL), define cookies |
| `/auth/login` | POST | Credenciais → cookies; resposta `{ user, expiresAt }`; mensagem de erro **uniforme** |
| `/auth/refresh` | POST | Cookie `__Secure-rt` → novos cookies (rotação); resposta `{ expiresAt }` |
| `/auth/logout` · `/auth/logout-all` | POST | Revoga a sessão atual / todas (`token_version++`) |
| `/auth/me` | GET | Usuário atual no **formato legado** + `expiresAt` |
| `/auth/verify-email` | POST | Confirma o e-mail com token de uso único |
| `/auth/forgot-password` · `/auth/reset-password` | POST | Resposta sempre genérica; token hash, TTL curto; reset revoga sessões |
| `/auth/change-password` | POST | Exige senha atual; revoga demais sessões |
| `/auth/account` | DELETE | Exige senha; marca conta, revoga tudo, publica `UserDeleted` |
| `/.well-known/jwks.json` | GET | Chaves públicas (atual + anteriores) com `kid`; cacheável |
| `/internal/auth/verify` | GET | Gateway: valida JWT (assinatura, `exp`, `aud`, `ver`) → `204` + `X-User-Id` ou `401`. **Não público** |

Limites de tentativa (Bucket4j, em memória enquanto houver 1 instância): ex.: 5 falhas/15 min por e-mail+IP com atraso
progressivo (sem bloqueio permanente, para não permitir DoS de conta), mais limite por IP em `/auth/*`.

---

## 5. Fases

### Fase 0 — Pré-requisitos (1–2 dias)
Executar a **contenção** do relatório (rotacionar `jwt.secret` e senhas, remover segredos do Git, HTTPS no Caddy); definir domínio
único; escolher provedor SMTP; inventariar usuários (e-mails duplicados/inválidos); exportar esquema do MySQL; confirmar as
decisões restantes (§7).

### Fase 1 — Esqueleto (2–3 dias)
Projeto Spring Boot (linha estável mais recente + Java 21 *(confirmar)*), Spring Modulith, Postgres + Flyway, configuração
tipada por variáveis de ambiente com **falha rápida**, `.env.example`, Testcontainers, Actuator restrito, CI (build, testes,
Dependency-Check, Gitleaks), Dockerfile multi-stage e `docker-compose` (identity, postgres, caddy, javalin, mysql).
**Pronto quando:** CI verde e o serviço sobe recusando configuração incompleta.

### Fase 2 — Contas e credenciais (3–4 dias)
Tabelas `account`/`account_role`/`audit_event`; `DelegatingPasswordEncoder` (`{bcrypt}` + `{argon2}` + upgrade);
`/auth/register` e `/auth/login` com Bean Validation, mensagens uniformes, limite de tentativas e auditoria.
**Pronto quando:** testes cobrem senha incorreta/inexistente (mesma resposta e tempo similar), força bruta e upgrade de hash.

### Fase 3 — Tokens e sessão (4–5 dias)
Carregamento da chave RSA por variável; `JwtEncoder`/`JwtDecoder` (Nimbus); JWKS; emissão/validação com `iss/aud/exp/ver`;
cookies `__Host-at`/`__Secure-rt`; `/auth/refresh` com **rotação, janela de renovação, tolerância, limite absoluto, reuso e
tolerância multi-aba**; `/auth/logout`, `/auth/logout-all`, `/auth/me`; mapa de `token_version` recente.
**Pronto quando:** testes provam: expira em 2 h; renova só dentro da janela/tolerância; sessão ociosa cai; reuso revoga a família;
rotação de chave não invalida sessões; relógio com *skew*.

### Fase 4 — Verificação de e-mail e recuperação (3–4 dias)
`email_token`, envio por SMTP (templates), `verify-email`, `forgot/reset/change-password`, exclusão de conta; todas as trocas
sensíveis incrementam `token_version`.

### Fase 5 — Integração com o gateway e o legado (2–3 dias)
Caddy: cookie→Bearer, `forward_auth` para `/internal/auth/verify`, bloqueio público de `/internal/*`, política de
`Origin`/`Sec-Fetch-Site` e CSRF de `/auth/*`, limpeza de `X-User-Id` vindo do cliente. **Javalin**: `AUTH_MODE=legacy|gateway`,
lê `X-User-Id`, porta só na rede interna; rotas de login/cadastro/JWT antigas desativadas no modo `gateway`.
**Pronto quando:** o Javalin rejeita requisições sem `X-User-Id` e ignora o cabeçalho se vier de fora.

### Fase 6 — Importação dos usuários atuais (2 dias)
CLI que lê `usuario` (MySQL) e cria `account` com **UUIDv7 novo**, `legacy_user_id`, `password_hash = {bcrypt}<hash atual>` e
`email_verified` conforme a decisão D6; relatório de conflitos e reconciliação; ensaio em staging com cópia do banco; idempotente.
**Pronto quando:** 100 % dos usuários de staging entram com a senha antiga e o `/auth/me` devolve o mesmo perfil de hoje.

### Fase 7 — Frontend (3–4 dias)
Mantém `FormSignin`/`FormSignup`; `AuthProvider` usa `/auth/*`; `expiresAt` em memória; interceptor com renovação proativa,
*single-flight* + Web Locks e repetição única em 401; telas de verificar e-mail, esqueci/redefinir senha e trocar senha; tratar
429; remover o código que dependia do cookie antigo/`/api/me`.

### Fase 8 — Endurecimento e operação (2 dias)
Cabeçalhos (HSTS, `nosniff`, CSP), alertas (falhas de login, 5xx, reuso de refresh), *runbook* de **rotação de chaves**, backup do
Postgres, retenção de auditoria sem PII excessiva, varredura de dependências.

### Fase 9 — Cutover e retorno (2 dias)
Ensaio completo em staging → janela curta: congelar cadastros, importar delta, publicar identity + frontend, trocar rotas do Caddy,
Javalin em `AUTH_MODE=gateway`. Todos entram de novo (cookies antigos deixam de valer). **Retorno:** reverter rotas e
`AUTH_MODE=legacy` (o código JWT antigo do Javalin permanece por uma versão). Depois: remover auth do Javalin e, passado o
período de segurança, `usuario.senha`.

**Estimativa:** 24–31 dias úteis para uma pessoa (≈ 5–6 semanas). É maior que a versão com Auth0 porque verificação de e-mail,
recuperação, limite de tentativas e auditoria passam a ser responsabilidade do serviço.

---

## 6. Testes

| Camada | Cobertura |
| --- | --- |
| Unidade | política de renovação (janela/tolerância/limite absoluto), validação de senha/idade/username, reuso de refresh |
| Integração | Postgres (Testcontainers), SMTP falso (GreenMail), relógio controlável (`Clock`) |
| Segurança | força bruta, enumeração de usuário, fixação/roubo de refresh, CSRF/Origin, JWT adulterado/`alg=none`/`kid` desconhecido, `aud/iss` errados, `X-User-Id` forjado, `/internal/*` externo |
| Contrato | `/auth/me` idêntico ao formato legado; JWKS válido para um *resource server* Spring de teste |
| Rotação | emitir com chave A, rotacionar para B, validar tokens de A e B durante a sobreposição |
| E2E (staging) | cadastro → verificar e-mail → login → uso por 2 h simuladas → renovação → logout geral → reset de senha |
| Migração | contagens, amostra de logins, idempotência |

---

## 7. Decisões restantes (com a recomendação)

| # | Decisão | Recomendado |
| --- | --- | --- |
| D1 | Janela/tolerância/limite absoluto da renovação | **10 min antes / 5 min depois / 7 dias** (§2.3) |
| D2 | Revogação com token de 2 h | **`token_version` já na v1**, verificado no gateway (muda minha recomendação anterior) |
| D3 | Cookies | **`__Host-at` + `__Secure-rt` (`Path=/auth`) e conversão cookie→Bearer no Caddy** |
| D4 | Hash de senha | **Argon2id com *upgrade* no login** (importa BCrypt atual) |
| D5 | Provedor SMTP e armazenamento do rate limit | SMTP à sua escolha; **limite em memória (1 instância)**, Redis depois |
| D6 | E-mails já existentes | **Importar como não verificados, sem bloquear login; exigir verificação para ações sensíveis** (trocar e-mail/senha, excluir conta) |
| D7 | MFA | **Fora da v1** (TOTP depois) |
| D8 | Validação do Javalin durante a transição | **`forward_auth` + `X-User-Id`** (em vez de o Javalin validar RS256 sozinho) |
| D9 | Versão do UUID | **UUIDv7** |

## 8. Riscos

- **Responsabilidade de segurança maior** (credenciais, recuperação, brute force). Mitigar com testes de segurança, revisão e
  dependências atualizadas.
- **Chave privada em variável de ambiente**: vazamento permite forjar qualquer token → proteger o `.env`/CI e ter *runbook* de rotação de emergência.
- **Javalin exposto** ou Caddy sem limpar `X-User-Id` ⇒ falsificação de identidade.
- **Gateway no caminho crítico** (`forward_auth`): se o identity cair, o tráfego legado cai; manter leve e monitorado.
- **Ponte temporária com o MySQL** (cadastro grava perfil lá): risco de inconsistência entre bancos → *saga* simples com
  compensação (criar conta → criar perfil → ativar) e remoção da ponte quando Perfil migrar.
- **Renovação multi-aba** e relógio do cliente: mitigados com Web Locks, tolerância de reuso e `expiresAt` vindo do servidor.
- **LGPD**: dados pessoais continuam sob sua guarda (sem terceiros); definir retenção de auditoria e exclusão completa da conta.
