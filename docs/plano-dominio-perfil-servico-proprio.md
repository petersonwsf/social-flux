# Plano — Domínio Perfil de usuário: `profile_service` (Spring Boot + PostgreSQL) e comunicação entre serviços

Data: 2026-11-10 · Complementa `analise-backend-e-migracao-spring-boot.md` e `plano-dominio-identidade-servico-proprio.md`.

> **Estado (2026-11-11): decisões D1–D12 aprovadas e implementadas** (Fases 1–7 do plano). Ajustes em relação ao texto abaixo:
> D7 = username **livre** (sem cooldown nem reserva; só lista de reservados no código), D10 = sem hotfix (sem dados reais),
> D5 = RabbitMQ sobe no `docker-compose.yml` da raiz. O PostgreSQL é o do compose da raiz, com **um banco por serviço**
> (`profile` ao lado do banco do auth) reutilizando as credenciais `IDENTITY_DB_*`. Implementação: `profile_service/`
> (README próprio), `authentication_service/` (cadastro orquestrado, outbox, token de serviço), `infra/` (Caddy, MySQL) e
> `FrontEnd/` (`/auth/me` só identidade + `GET /api/usuarios/me`; fotos em `/media/avatars`).
> Pendências conhecidas: ver o fim deste arquivo (§14).

> **Premissa (definida pelo dono do projeto): não existem dados em nenhum banco ainda.** Logo, não há importação de usuários,
> migração de fotos, compatibilidade com ids/hashes existentes, janela de congelamento nem reconciliação inversa. Os bancos novos
> nascem do zero e o esquema do MySQL legado deixa de ser “inferido e incerto”: passa a ser o `infra/mysql/schema.sql` que nós
> mesmos definimos. Isso também **dispensa a importação em massa** (Fase 6 do plano de identidade) e deixa o `legacy_user_id`
> como simples ponte de ids novos.

**Nada deste plano foi implementado nem executado.** Base: leitura do código de `BackEnd/` (UsuarioController, UsuarioService,
UsuarioDao, Usuario, ImagemUtil, ImagemController, RelacaoController, DAOs com JOIN em `usuario`, App), do
`authentication_service/` (AccountService, AuthController, InternalAuthController, JdbcLegacyProfile, V1__identity.sql), do
`infra/` (Caddyfile, schema.sql inferido) e do frontend (usuarioService, types, Avatar). Itens marcados *(confirmar)* dependem de
versões/documentação de terceiros ou do esquema real do MySQL.

---

## 1. O que o legado faz hoje no domínio Perfil

| Rota legada | Hoje | Observação |
| --- | --- | --- |
| `GET /api/usuarios?id=` | Perfil por id inteiro | **Devolve e-mail, telefone, sexo e data de nascimento de qualquer usuário** (`UsuarioController:40-45` usa `resultSetToUsuarioComplete`; só `senha` é anulada; `Usuario` expõe esses getters). **Achado novo, não está no relatório**: vazamento de PII para qualquer usuário logado |
| `GET /api/usuarios?username=` | Perfil por username | Mapper reduzido (sem PII) |
| `GET /api/usuarios/search?search=` | Busca por `LIKE %x%` em username/nome, `LIMIT 10` | Sem escape de `%`/`_`; sem `search` não responde (fica pendurado); sem ordenação |
| `PATCH /api/usuarios/{id}` | Edita username/nome/biografia/privado | **Usa o `id` do corpo** (IDOR), `Integer !=` (LOG-03), `privado` nulo → NPE, username sem regex |
| `PATCH /api/img/usuarios/{id}` | Troca a foto (multipart `foto_perfil` + `foto_antiga`) | IDOR, `foto_antiga` sem sanitização (**exclusão arbitrária de arquivos**), tipo por extensão do cliente, sem limite de tamanho |
| `GET /api/img/{id_usuario}/{id_imagem}` | Serve a imagem | Sem autenticação, sem normalização de caminho. **Compartilhado com imagens de postagem** (mesma pasta `images/{id}/`) |
| `POST /api/usuarios/{id}` (excluir) | Exclui conta | Já desativado no modo gateway; hoje é `DELETE /auth/account` |

Dependências do resto do legado em `usuario` (JOINs diretos no MySQL): 6 consultas em `PostagemDao`, 4 em `RelacaoDao`,
1 em `NotificationDao`, 1 em `UsuarioDao.buscarRelacoesEmComum`. Eles usam `id`, `username`, `nome`, `foto_perfil`, `privado`,
`biografia`. **Isso é o maior obstáculo da transição** (ver §5).

O que o frontend consome: `Usuario { id:number, username, nome, biografia, foto_perfil, privado, email?, telefone?, sexo? }`,
`AuthUser = Usuario + accountId`; `Avatar` monta a URL com `getUserImageUrl(user.id, foto_perfil)` → `/api/img/{id}/{arquivo}`;
rota `/perfil/:username`. O `/auth/me` hoje devolve o perfil lido do MySQL pela ponte `LegacyProfilePort`.

---

## 2. Como relacionar Perfil ↔ Conta ↔ demais domínios

### 2.1 Identidade
| Conceito | Decisão |
| --- | --- |
| Id canônico | **UUIDv7 da conta** (`sub` do JWT). O perfil tem **o mesmo UUID** como chave primária (1:1, já decidido no plano de identidade) |
| Outros domínios | Guardam `user_id uuid` **sem FK entre bancos**. A integridade vem de eventos (`ProfileDeleted` → limpar dados) + job de reconciliação |
| Id inteiro legado | **Temporário.** O `profile_service` é o dono do mapeamento `uuid ↔ legacy_id` (coluna `legacy_id`, sequence começando em 1, pois não há ids pré-existentes). Sai quando o último domínio legado migrar |
| Claim `legacy_uid` / `X-User-Id` | Continuam como hoje. O que muda é **quem gera o inteiro**: o `profile_service` (não mais o INSERT da ponte no MySQL) e o `authentication_service` o grava em `account.legacy_user_id` a partir da resposta da criação do perfil |
| JSON para o frontend | Durante a transição `id` = inteiro legado (nada quebra) e `accountId` = UUID. Quando todos os domínios usarem UUID, `id` passa a ser o UUID (mudança de versão da API) |

### 2.2 Quem é dono de quê
| Dado | Dono | Quem pode ver |
| --- | --- | --- |
| e-mail, senha, status, papéis, verificação | `authentication_service` | só o dono (`/auth/me`) |
| username, nome de exibição, biografia, foto, `privado` | **`profile_service`** | qualquer usuário autenticado (visão pública) |
| telefone, sexo, data de nascimento | **`profile_service`** *(decisão D3)* | **só o dono**; nunca em listas, busca, eventos ou respostas de terceiros |
| quem segue quem, solicitações | futuro domínio Relações | conforme privacidade |
| postagens, curtidas, notificações | domínios próprios | idem |

`privado` é **dado do perfil**, mas a **regra de acesso ao conteúdo** de uma conta privada é do domínio dono do conteúdo
(Postagens/Relações), que precisa conhecer o `privado` de cada autor — via evento (cópia local) ou consulta (ver §3.3).

### 2.3 Fluxos entre `authentication_service` e `profile_service`

**Cadastro** (o contrato `POST /auth/register` não muda; a orquestração é do auth, que já faz a saga hoje):
```
Front ─ POST /auth/register ─► auth: valida e-mail/senha, gera UUIDv7 da conta
                                 ├─ PUT /internal/profiles/{uuid}  (token de serviço) ─► profile
                                 │     valida username (regex, reservados, unicidade), nome, idade ≥ 18, telefone
                                 │     cria perfil PENDING + legacy_id  ──► 201 {legacyId}   | 409 username_taken | 422 ...
                                 ├─ grava account (legacy_user_id = legacyId) na transação local
                                 │     falhou? ─► DELETE /internal/profiles/{uuid} (compensação, como hoje)
                                 └─ outbox: AccountRegistered ─► profile ativa (PENDING → ACTIVE) e projeta no MySQL
```
Perfil `PENDING` é visível só ao dono; um job remove `PENDING` com mais de 30 min (órfãos de falha entre os dois passos).
Os códigos de erro (`username_taken`, `underage`, `invalid_phone`...) são mapeados 1:1 para o que o frontend já trata.

**Exclusão de conta**: `DELETE /auth/account` (já existe) grava `AccountDeleted` no outbox em vez de chamar `legacy.delete()`;
o `profile_service` marca `DELETED`, apaga avatar e dados pessoais, remove a linha projetada no MySQL (o `ON DELETE CASCADE`
limpa postagens/relações/curtidas legadas) e publica `ProfileDeleted` para os demais domínios.

**`/auth/me`**: hoje compõe o perfil lido do MySQL. Opções em D4; recomendo `/auth/me` devolver **só identidade**
(`accountId`, e-mail, `emailVerified`, `expiresAt`, `renewAt`) e o frontend chamar `GET /api/usuarios/me` — evita que o auth dependa
do profile em tempo de leitura. Para não mexer no frontend de imediato, o auth pode manter a composição via `ProfileClient`
(substituto direto do `JdbcLegacyProfile`, que já está atrás da interface `LegacyProfilePort`).

---

## 3. Comunicação entre serviços (visão pós-migração)

### 3.1 Princípios
1. **Banco por serviço**, nenhum JOIN ou FK entre bancos. Um banco/role por serviço no mesmo Postgres (dev) — separável depois sem código.
2. **Leitura do usuário final** passa pelo gateway; cada serviço é *resource server* (valida o JWT localmente via JWKS, autoriza pelo `sub`, nunca por id do cliente).
3. **Síncrono** só quando o chamador precisa da resposta para continuar (ex.: criar perfil no cadastro). **Assíncrono (eventos)** para propagar fatos (perfil mudou, conta excluída) — o produtor não conhece os consumidores.
4. **Sem biblioteca compartilhada de domínio.** Só um *starter* pequeno e versionado com configuração do resource server, formato de erro `{mensagem, sucesso, codigo}` e envelope de eventos.

### 3.2 Padrão recomendado
| Tipo | Tecnologia | Quando |
| --- | --- | --- |
| Usuário → serviço | HTTPS REST/JSON via Caddy; cookie → `Authorization: Bearer` no gateway; serviço valida JWT RS256 (JWKS) | sempre |
| Serviço → serviço (síncrono) | REST/JSON em `/internal/**` (nunca roteado pelo Caddy), com **token de serviço**: JWT RS256 curto (5 min), `sub=svc:<nome>`, `aud=<destino>`, `scope`; assinado pelo auth (que já tem a chave) e validado pelo destino pelo mesmo JWKS. Timeout curto, retry só em operações idempotentes (`PUT`/`DELETE`), circuit breaker (Resilience4j), testes de contrato (Pact ou Spring Cloud Contract) | consistência imediata necessária; baixa frequência |
| Serviço → serviços (assíncrono) | **Transactional outbox** (evento gravado na mesma transação do dado) → **RabbitMQ** (exchange `topic`, filas duráveis por consumidor, DLQ, *retry* com atraso) | propagar fatos; vários consumidores |
| Consumidor | **Inbox/idempotência** (tabela `processed_message(id)`), tolera duplicata e fora de ordem (campo `aggregateVersion`) | todo consumidor |
| Novo serviço entrando | Subscreve eventos para o dia a dia + **endpoint de bootstrap** `GET /internal/profiles?updatedAfter=&cursor=` para carga inicial da cópia local | adição de serviços |

Por que RabbitMQ e não Kafka: o volume deste produto não justifica a operação do Kafka; o único ganho decisivo dele seria *replay*
do histórico, que o endpoint de bootstrap cobre. Por que não gRPC: o front já é REST, a equipe é pequena e as chamadas internas
são raras. Ambos são reversíveis: o envelope de evento e o contrato REST são independentes do transporte. *(Confirmar a escolha em D5.)*

**Envelope de evento (versionado, só mudanças aditivas):**
```json
{ "id": "uuid", "type": "profile.updated.v1", "occurredAt": "…Z", "aggregateId": "<uuid do perfil>",
  "aggregateVersion": 7, "data": { "username": "ana", "displayName": "Ana", "avatarKey": "av_….jpg",
                                   "isPrivate": false, "legacyId": 123 } }
```
Eventos **nunca carregam PII privada** (telefone, nascimento, sexo, e-mail) — vão para vários consumidores.

### 3.3 Como os outros domínios obtêm dados do perfil (o que antes era JOIN)
| Necessidade | Solução | Consistência |
| --- | --- | --- |
| Exibir autor (id, username, nome, foto) em feed, notificações, seguidores | **Cópia local somente-leitura** (`user_snapshot`) em cada serviço consumidor, alimentada por `profile.*` | eventual (milissegundos a poucos segundos) |
| Decidir visibilidade de conta privada | Mesma cópia local (`isPrivate`), com `aggregateVersion` para ignorar eventos antigos | idem — **risco**: janela curta em que uma conta recém-privada ainda aparece como pública; se for inaceitável, consulta síncrona com cache de TTL ≤ 10 s só para esta decisão |
| Dado raro/pontual (tela de configurações) | `GET /internal/profiles/{id}` ou `:batchGet` | forte |

### 3.4 O que **não** fazer
Banco compartilhado/lido por outro serviço; chamar o profile em loop por item de feed (N+1 de rede); fan-out de eventos com PII;
dois serviços escrevendo o mesmo agregado; evento sem versão; "event-carried" com o objeto inteiro da entidade interna.

---

## 4. Escopo do `profile_service`

**Dentro:** criar/ler/editar perfil próprio; visão pública por username e por id; busca; foto de perfil (armazenamento e entrega);
flag `privado`; regras e histórico de username; PII do dono (telefone, sexo, nascimento); reação a `AccountRegistered`/`AccountDeleted`;
endpoints internos (criar/compensar, `batchGet`, bootstrap); eventos `profile.*`; projeção temporária no MySQL legado;
importação dos perfis existentes.

**Fora:** login/senha/e-mail (auth); relações/seguidores e contagens (Relações); postagens/mídia de postagem; notificações.
`GET /api/relacao/compartilhado` (hoje em `UsuarioDao`) pertence às Relações e **fica no legado**.

Estrutura (mesma convenção do auth): `config/ domain/ service/ security/ web/ storage/ events/ legacy/` — `legacy/` (projetor
MySQL + importação) é removido ao fim.

---

## 5. Transição com o legado: projeção do `usuario` no MySQL

Enquanto Postagens/Relações/Notificações estiverem em Javalin, os JOINs em `usuario` precisam continuar funcionando.
**Opções**

| Opção | Prós | Contras |
| --- | --- | --- |
| **A. `usuario` vira projeção (recomendada)**: `profile_service` é a única origem de escrita; um projetor (no próprio profile, lendo o outbox) faz *upsert* idempotente no MySQL | Legado não muda (JOINs intactos); sem trabalho descartável; some quando o último domínio migrar | Atraso pequeno e dupla escrita lógica; precisa reconciliação |
| B. Reescrever os 12 JOINs do legado para chamar a API do profile | Sem projeção | Muito código legado descartável; N+1 de rede; alto risco |
| C. Migrar Relações/Postagens junto com Perfil | Sem projeção | Escopo triplicado; viola “um domínio por vez” |

Detalhes da opção A:
- O projetor escreve só colunas de perfil (`id`=`legacy_id` explícito, `username`, `nome`, `biografia`, `foto_perfil`, `privado`, `data_criacao`).
  **Como não há dados, redefinimos o `usuario` do MySQL como tabela de projeção enxuta** (sem `email`, `senha`, `telefone`, `sexo`,
  `data_nascimento`) em `infra/mysql/schema.sql`: nada de valores-placeholder e nenhuma PII fora do profile/auth. Consequência no
  legado: remover (ou deixar morto) o que lê essas colunas — `resultSetToUsuarioComplete`, `buscarPorEmail`, `inserir` e as rotas
  `/api/usuarios*`/`/api/img/usuarios/*`, que passam ao profile — e conferir os demais DAOs (os JOINs usam só colunas mantidas).
- `foto_perfil` projetado carrega a nova chave `av_<uuid>.jpg`; ver §6.5 sobre a entrega da imagem.
- No gateway, `/api/usuarios*` e `PATCH /api/img/usuarios/*` passam a ir **ao profile_service** *antes* do `handle /api/*` genérico
  (ordem dos `handle` no Caddyfile *(confirmar)*). O legado mantém só leitura de `usuario`.
- Reconciliação: job simples compara contagem/`version` entre Postgres e MySQL e reaplica diferenças (rede de segurança contra evento perdido).
- Sem dados, **não há cutover arriscado**: o legado simplesmente deixa de ter essas rotas. Rollback = reverter o Caddyfile e o
  código (nada a reconciliar em sentido inverso).
- **Com banco vazio, vale reconsiderar a ordem**: migrar Relações/Postagens logo depois (ou junto) elimina a projeção mais cedo.
  A projeção é o custo de manter o legado vivo entre domínios; se o legado for substituído em poucas semanas, é custo curto.

---

## 6. Modelo de dados (PostgreSQL, Flyway) e API

### 6.1 Esquema
```sql
create extension if not exists citext;  create extension if not exists pg_trgm;  create extension if not exists unaccent;

create sequence legacy_id_seq;                              -- TEMPORÁRIA; começa em 1 (sem dados pré-existentes)

create table profile (
  id            uuid primary key,                           -- = account.id (sub do JWT); gerado pelo auth
  legacy_id     integer unique not null default nextval('legacy_id_seq'),   -- TEMPORÁRIO
  username      citext not null unique,
  display_name  varchar(120) not null,
  bio           varchar(500),
  avatar_key    varchar(80),                                -- ex.: av_0192….jpg (nome gerado no servidor)
  is_private    boolean not null default false,
  phone         varchar(20)  not null,                      -- PII: só o dono
  sex           char(1)      not null check (sex in ('M','F')),
  birth_date    date         not null,
  status        varchar(10)  not null default 'PENDING' check (status in ('PENDING','ACTIVE','DELETED')),
  confirmed_at  timestamptz,                                -- AccountRegistered recebido
  version       bigint       not null default 1,            -- incrementa a cada mudança; vai nos eventos
  username_changed_at timestamptz,
  created_at    timestamptz  not null,
  updated_at    timestamptz  not null,
  constraint username_format check (username ~ '^[a-z0-9](?:[a-z0-9._]{1,28})[a-z0-9]$' and username !~ '\.\.')
);
create index profile_search_trgm on profile using gin ((lower(unaccent(username))) gin_trgm_ops, (lower(unaccent(display_name))) gin_trgm_ops);

create table username_reservation (                         -- nomes liberados ficam reservados ao dono anterior
  username citext primary key, profile_id uuid not null, reserved_until timestamptz not null);

create table reserved_username (username citext primary key);   -- admin, api, auth, perfil, suporte, ...

create table outbox_event (id uuid primary key, type text not null, aggregate_id uuid not null, aggregate_version bigint not null,
  payload jsonb not null, created_at timestamptz not null, published_at timestamptz);
create table processed_message (id uuid primary key, processed_at timestamptz not null);   -- inbox / idempotência
```
O uso de `unaccent` com índice funcional exige função *immutable* (envoltório) *(confirmar)*. Timestamps em UTC (`timestamptz`), corrigindo LOG-11 neste domínio.

### 6.2 API pública (mantém o contrato atual do frontend)
Vista **pública**: `{ id (legacy int), accountId, username, nome, biografia, foto_perfil, foto_url, privado }`.
Vista **do dono**: pública + `{ telefone, sexo, data_nascimento, data_criacao }` (e-mail continua só em `/auth/me`).

| Rota | Autorização / regra | Mudança em relação ao legado |
| --- | --- | --- |
| `GET /api/usuarios?username=` e `?id=` | Autenticado. Sempre vista pública (a do dono se for o próprio). 404 uniforme para inexistente/`PENDING`/`DELETED` | **Fecha o vazamento de PII**; `id` aceita o inteiro legado e resolve pelo mapeamento |
| `GET /api/usuarios/me` *(novo)* | Dono | Evita compor no auth |
| `GET /api/usuarios/search?search=` | Autenticado; mín. 2 e máx. 50 caracteres; escape de `%`/`_`; *unaccent* + ordenação (prefixo exato > prefixo > trigram); `LIMIT 10`; só `ACTIVE`; rate limit por conta | `400` em vez de ficar pendurado; sem `LIKE %x%` ingênuo |
| `PATCH /api/usuarios/{id}` | `{id}` precisa resolver para o `sub`; **o `id` do corpo é ignorado**; `403` se divergir. Campos opcionais (`privado` ausente = não muda) | Fecha IDOR, LOG-03/04; valida regex/reservados/cooldown; `409 Username já está em uso` |
| `PATCH /api/img/usuarios/{id}` | Idem; `foto_antiga` **ignorado** (o servidor conhece a chave atual) | Fecha IDOR e exclusão arbitrária |
| `GET /media/avatars/{arquivo}` | Público, cache imutável | Ver §6.5 |

Formato de erro igual ao do auth: `{ "mensagem", "sucesso": false, "codigo" }`. Optimistic locking opcional via `version`/`If-Match`.

### 6.3 API interna (`/internal/**`, token de serviço, fora do Caddy)
`PUT /internal/profiles/{id}` (idempotente; criação no cadastro) · `DELETE /internal/profiles/{id}` (compensação, só `PENDING`) ·
`GET /internal/profiles/{id}` · `POST /internal/profiles:batchGet` · `GET /internal/profiles?updatedAfter=&cursor=` (bootstrap).

### 6.4 Regras de autorização e privacidade (corrigem o relatório)
| Falha | Correção neste domínio |
| --- | --- |
| SEC-02 / LOG-04 | Identidade só do `sub`; `id` de path/corpo é verificado, nunca usado como autoridade; Bean Validation; `privado` nulo tratado |
| LOG-03 | `Objects.equals`/UUID; teste de regressão com `legacy_id > 127` |
| PII em `GET ?id=` (novo) | DTOs distintos (público × dono), sem serialização de entidade; testes que varrem o JSON procurando campos proibidos |
| LOG-12 | busca com escape e limite; sem `search` → 400 |
| SEC-04 / SEC-05 | ver §6.5 |
| Squatting/personificação | username: 3–30, `^[a-z0-9._]`, sem `..`, não começa/termina com `.`; lista de reservados; troca com *cooldown* (D7) e nome antigo reservado ao dono anterior |
| Enumeração | `404` uniforme; busca com rate limit |
| Conta privada | O perfil (nome/foto/bio) continua visível; o **conteúdo** (posts, seguidores) é controlado pelo domínio dono do conteúdo, usando `isPrivate` do evento (D6) |

### 6.5 Foto de perfil
- Armazenamento atrás de `StorageService` (disco fora do classpath, configurável; S3/MinIO depois). Chave gerada no servidor: `av_<uuidv7>.jpg`.
- Validação: limite de 5 MB e 16 MP (leitura do cabeçalho com `ImageReader` antes de decodificar); *magic bytes* PNG/JPEG/WebP; decodificar e **re-codificar** (remove EXIF/payload), recorte central 256×256 (hoje 200×200 *(confirmar tamanho)*); apagar a imagem anterior pela chave do banco.
- Entrega: `GET /media/avatars/{chave}` com `Content-Type` fixo, `nosniff`, `Cache-Control: public, max-age=31536000, immutable`.
- **Conflito de URL na transição:** o frontend monta `/api/img/{id}/{arquivo}` e esse caminho serve também imagens de postagem do legado, então o gateway não consegue separar por rota. Proposta: o frontend ajusta o helper `getUserImageUrl` (um ponto só, usado por `Avatar`): se a chave começa com `av_` → `/media/avatars/{chave}`; senão, caminho legado. Não há fotos antigas a migrar.
- Avatar público (sem login) é aceitável porque o nome é imprevisível; alternativa em D8.

---

## 7. Eventos

| Produz | Quando | Consumidores previstos |
| --- | --- | --- |
| `profile.created.v1` | ativação (`PENDING→ACTIVE`) | projetor MySQL; futuros serviços (snapshot) |
| `profile.updated.v1` | username/nome/bio/foto mudam (traz snapshot público + `aggregateVersion`) | projetor; snapshots |
| `profile.privacy-changed.v1` | `isPrivate` muda (também em `updated`; evento próprio facilita priorização) | Relações/Postagens/Notificações |
| `profile.deleted.v1` | exclusão | todos os domínios (limpar dados do usuário) |

| Consome | Origem | Ação |
| --- | --- | --- |
| `account.registered.v1` | auth | ativa o perfil |
| `account.deleted.v1` | auth | apaga/anonimiza |
| `account.disabled.v1` *(futuro)* | auth | oculta o perfil |

O auth precisa ganhar o **outbox** (já previsto e ainda não implementado em `authentication_service/README.md`).

---

## 8. Estrutura de pastas e infra
- `profile_service/` ao lado de `authentication_service/` (mesmo padrão: Maven, Java 21, Spring Boot 4.1.x *(confirmar patch)*, Flyway, Spring Security resource server com `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` apontando para o JWKS do auth, `.env` na raiz, testes de integração com Postgres embutido (zonky) como no auth, `DevServer`).
- `docker-compose.yml`: banco `profile` (ou schema + role próprio), `rabbitmq`, serviço `profile_service` (perfil `apps`), scripts `scripts/dev/run-profile.ps1`.
- `infra/caddy/Caddyfile`: rotas novas (`/api/usuarios*`, `PATCH /api/img/usuarios/*`, `/media/avatars/*`) → profile; `/internal/*` continua bloqueado.
- Configuração só por variáveis de ambiente: `PROFILE_DB_*`, `PROFILE_STORAGE_DIR`, `AUTH_JWKS_URI`, `JWT_ISSUER/AUDIENCE`, `RABBITMQ_*`, `SERVICE_TOKEN_*`, `LEGACY_DB_*` (só o projetor).

---

## 9. Fases

| Fase | Entrega | Pronto quando | Dias |
| --- | --- | --- | --- |
| 0 | Decisões D1–D12 fechadas (hotfix do legado só se algum ambiente com usuários reais for aberto antes da Fase 6, ver D10) | decisões registradas | 0,5–1 |
| 1 | Esqueleto `profile_service`: build, Flyway, resource server via JWKS, formato de erro, Dockerfile/compose, CI | sobe recusando config incompleta; token do auth é aceito, adulterado não | 2–3 |
| 2 | Núcleo: perfil, regras de username, vistas pública/dono, busca, `PATCH` com autorização | testes negativos de IDOR em cada rota mutável; varredura de PII; `legacy_id > 127` passa | 4–5 |
| 3 | Foto de perfil (`StorageService`, validação, re-codificação, entrega) | imagem falsa `.png`, gigante, `foto_antiga` malicioso e traversal rejeitados | 3 |
| 4 | API interna + token de serviço + `ProfileClient` no auth (substitui `JdbcLegacyProfile`); cadastro/compensação/exclusão pelo novo caminho | 25 testes do auth continuam verdes + novos de saga (falha do profile, falha do auth, perfil órfão) | 3–4 |
| 5 | Outbox nos dois serviços + RabbitMQ + inbox; eventos `account.*`/`profile.*`; projetor MySQL; endpoint de bootstrap; reconciliador | alterar perfil reflete no `usuario` do MySQL; evento duplicado/fora de ordem é inócuo; broker fora do ar não perde evento | 4–5 |
| 6 | Gateway e frontend: rotas, `getUserImageUrl`, `GET /api/usuarios/me`, `/auth/me` só com identidade (se D4 aprovado) | fluxo completo no navegador: cadastro, editar perfil, foto, busca, excluir conta | 2–3 |
| 7 | Limpeza: **remover do auth a ponte MySQL** (pacote `legacy`, driver e `LEGACY_DB_*`); enxugar `usuario` no `schema.sql`, remover do legado rotas/código de usuário e leitura de colunas que não existem mais; remover do auth as mensagens/fluxos que dependiam do perfil legado | auth sobe sem MySQL; legado compila e testa com o `usuario` enxuto; E2E completo verde | 2 |

~~Importação de usuários/fotos e cutover com congelamento~~ **não existem mais** (sem dados). A implantação é um deploy comum.

**Estimativa:** ≈ 19–24 dias úteis para uma pessoa (≈ 4–5 semanas). O que mais pesa é a integração auth ↔ profile e os eventos (fases 4 e 5), não o CRUD em si.

---

## 10. Testes

| Camada | Cobertura |
| --- | --- |
| Unidade | regras de username (regex, reservados, cooldown, reserva), idade, validação de imagem |
| Integração | Postgres embutido, relógio controlável, storage em diretório temporário, broker de teste (Testcontainers RabbitMQ ou fake), MySQL embutido/Testcontainers para o projetor |
| Segurança | IDOR (cada `PATCH`), `id` do corpo ≠ `sub`, PII ausente das vistas públicas e dos eventos, JWT inválido/`aud` errado/token de serviço em rota de usuário e vice-versa, `/internal/**` sem token de serviço, upload malicioso, traversal |
| Contrato | JSON idêntico ao tipo `Usuario` do frontend; contrato `auth ↔ profile` (Pact/SCC) |
| Resiliência | profile fora do ar no cadastro (sem conta órfã); broker fora do ar (evento não se perde); evento duplicado/fora de ordem |
| E2E | cadastro → perfil criado (Postgres) e projetado (MySQL) → editar → foto → buscar → excluir conta (some em tudo) |

---

## 11. Riscos
- **Dupla escrita lógica (Postgres → MySQL):** divergência temporária. Mitigar com outbox, upsert idempotente e reconciliador.
- **Atraso da projeção** pode quebrar a leitura imediata no legado após cadastro/edição (ex.: criar postagem logo após cadastrar). Mitigar: projeção no mesmo ciclo do outbox (ms) e `PENDING` só ativa depois de projetado; medir.
- **Auth passa a depender do profile no cadastro** (dependência síncrona). Mitigar: timeout/circuit breaker, erro claro, compensação; a leitura do dia a dia não depende.
- **`privado` eventualmente consistente** pode expor conteúdo por segundos (§3.3).
- **Complexidade operacional** (RabbitMQ, outbox, mais um serviço) para uma base pequena; justificada pela meta de novos serviços, mas é custo real.
- **Esquema do MySQL**: sem dados, o `schema.sql` passa a ser a fonte da verdade; o risco restante é o legado ainda referenciar colunas removidas (cobrir com a Fase 7 e testes do legado). Se existir algum ambiente já com esquema real diferente, ele precisa ser recriado.
- **Segredos antigos no histórico do Git** continuam pendentes (rotacionar) — independe deste plano.

---

## 12. Decisões que precisam do seu parecer

| # | Decisão | Recomendado |
| --- | --- | --- |
| D1 | Quem orquestra o cadastro | **Auth orquestra** (cria perfil sincronamente, compensa se falhar, evento confirma) · alternativa: frontend em 2 passos |
| D2 | Banco | **Um banco por serviço (`profile`) com role próprio** na mesma instância · alternativa: schema `profile` no mesmo banco do auth |
| D3 | Onde ficam telefone, sexo e nascimento | **Perfil** (só o dono vê; idade é regra de perfil) · alternativa: auth (identidade/conformidade) |
| D4 | `/auth/me` | **Só identidade + `GET /api/usuarios/me` no front** · alternativa: manter a composição via `ProfileClient` por enquanto |
| D5 | Transporte assíncrono | **Outbox + RabbitMQ agora** (auth↔profile já precisam) · alternativas: Kafka, ou só HTTP com retry via outbox no começo |
| D6 | Visibilidade de conta privada nos outros domínios | **Cópia local por evento** · alternativa: consulta síncrona com cache curto (mais forte, mais acoplada) |
| D7 | Troca de username | **1 troca a cada 14 dias; nome antigo reservado 30 dias ao dono anterior** · alternativas: livre; ou proibida |
| D8 | Avatar | **Público e imutável em `/media/avatars/{chave}`** · alternativa: exigir login |
| D9 | Foto de contas privadas / biografia | **Visíveis a qualquer logado** (como hoje na UI) · alternativa: só a seguidores |
| D10 | Hotfix do legado antes da migração (PII em `?id=`, IDOR de perfil/foto) | **Dispensável**: sem dados reais não há PII a vazar e a Fase 6 move as rotas para o profile. Só vale se algum ambiente com usuários reais for aberto antes disso |
| D11 | Ordem dos domínios (com banco vazio) | **Perfil agora; Relações logo em seguida** para encurtar a vida da projeção MySQL · alternativa: Perfil + Relações num só serviço inicialmente |
| D12 | Id exposto ao frontend | **Manter `id` inteiro + `accountId` durante a transição** (os domínios legados ainda usam inteiro) · como não há dados, o front pode adotar UUID assim que o último domínio migrar, sem migração de dados |

## 13. O que não foi verificado
Nada foi executado. Não inspecionei o servidor de produção nem confirmei que realmente não há dados em nenhum ambiente (premissa sua); ordem de avaliação dos `handle` no Caddy,
versões exatas (Spring Boot 4.1.x, RabbitMQ client, zonky) e a viabilidade do índice `unaccent` + trigram precisam ser confirmados na Fase 1.

---

## 14. Estado da implementação, o que foi verificado e pendências (2026-11-11)

**Verificado executando:** testes do `profile_service` (41, PostgreSQL real embutido, JWKS local, JWT RS256 real) e do
`authentication_service` (31); `tsc`/`eslint` do frontend; compilação do legado. **Ponta a ponta com a infra real** (PostgreSQL,
MySQL e RabbitMQ em containers, auth e profile como jars, Caddy como gateway, navegador simulado com cookies): cadastro →
perfil criado com o mesmo UUID → evento ativa o perfil → projeção no `usuario` do MySQL; leitura sem PII; IDOR 403; edição,
busca, foto (nosniff/imutável), revogação imediata (`logout-all`) no profile via `forward_auth`, exclusão de conta propagada por
eventos até o MySQL e o disco; broker fora do ar (cadastro segue, evento fica no outbox e é entregue ao voltar);
profile fora do ar (503 `profile_unavailable`, nenhuma conta órfã). O teste achou um defeito no Caddyfile (`/internal/*`
não respondia 404 — corrigido com `handle`).

**Não verificado:** navegador real com o frontend (só tipos/lint; o fluxo foi exercitado por HTTP); o `docker compose --profile apps`
(build das imagens do `profile_service`/`authentication_service`/legado); o fluxo de produção (cookies `__Host-`/HTTPS).

**Pendências / riscos conhecidos:**
- Perfil `PENDING` órfão (falha do auth *e* da compensação) mantém o username ocupado até limpeza manual.
- Revogação imediata depende do gateway (`forward_auth`); o `profile_service` isolado só valida assinatura/validade (≤ 2 h).
- `legacy_id` e a tabela `usuario` do MySQL são temporários (removidos quando Relações/Postagens/Notificações migrarem).
- O backend legado perdeu as rotas de usuário/autenticação; o modo `AUTH_MODE=legacy` não funciona mais (só `gateway`).
- Volume MySQL antigo precisa ser recriado (esquema novo de `usuario`); um RabbitMQ novo deve ser criado com `docker compose up -d`.
- Importação/segredos antigos no histórico do Git: rotacionar continua pendente (independe deste serviço).
