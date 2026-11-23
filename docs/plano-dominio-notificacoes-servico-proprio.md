# Plano — Domínio Notificações: `notification_service` (notificações geradas por eventos + tempo real por SSE)

Data: 2026-11-15 · Complementa `analise-backend-e-migracao-spring-boot.md`, `plano-dominio-postagens-servico-proprio.md` e
`plano-dominio-relacoes-grafo-social.md`. **Última migração do legado:** ao final, o `BackEnd/` (Javalin) e o MySQL deixam de existir.

> **Estado (2026-11-16): implementado — Fases 1 a 8 (estado e pendências em §15).** Todas as decisões fechadas (D1–D16, §13.1 e §13.2).
> Base: leitura de `BackEnd/` (`NotificationController`, `NotificationService`, `NotificationDao`, `Notification`,
> `NotificationCategory`, `AuthMiddleware`, `App`), `infra/mysql/schema.sql`, `infra/caddy/Caddyfile`, `docker-compose.yml`,
> READMEs do `post_service`, `social_graph_service`, `profile_service`, `authentication_service` e `libs/socialflux-events`,
> e do frontend (`notificationService`, `NotificationsProvider`, `notificationsContext`, `Notification.tsx`, `Notifications.tsx`,
> `Sidebar`/`FooterBar`, `useCurtidas`, `usePostagemActions`, `useRelacao`, `useRelacoesPendentes`, `lib/api.ts`, `lib/session.ts`).
> Premissa herdada: **não existem dados reais em nenhum banco** (sem importação das notificações do MySQL).

---

## 1. Resumo executivo

1. Criar o **`notification_service`** (Spring Boot 4.1 · Java 21 · PostgreSQL próprio, banco `notification`, porta **8085**), no
   mesmo padrão do `post_service`: resource server JWT, Flyway, inbox pela `socialflux-events`, JDBC, erros `{ mensagem, sucesso, codigo }`.
2. **As notificações deixam de ser criadas pelo frontend.** Hoje qualquer usuário logado pode forjar uma notificação em nome de
   qualquer outro (`POST /api/notification` aceita `sender`/`receiver` do corpo). O novo serviço **não tem rota de criação**: ele
   gera as notificações a partir dos eventos que já existem — `post.liked.v1`, `post.created.v1` (resposta), `follow.created.v1`,
   `follow.requested.v1` — e as apaga com os eventos de desfazer (`post.unliked.v1`, `post.deleted.v1`, `follow.removed.v1`…).
   **Nenhum serviço existente precisa mudar** para isso: os eventos já carregam tudo (§4).
3. **Tempo real por SSE** (`GET /api/notificacoes/stream`, `text/event-stream`): o navegador abre um `EventSource` (cookie da
   própria origem → gateway → `forward_auth`) e recebe `notificacao`, `removida` e `contagem` assim que o evento é processado.
   Reconexão com `Last-Event-ID` reenvia o que se perdeu. O stream tem **duração máxima** e fecha no vencimento do JWT, para que
   a revogação (logout geral, troca de senha) e a sessão deslizante continuem valendo (§7).
4. Corrige no domínio: notificações forjáveis, **leitura das notificações de qualquer usuário** (`?userId=` do cliente),
   **marcar como vistas as de qualquer usuário** (`PATCH /{id_receiver}`), lista sem paginação, falha silenciosa da notificação
   quando o frontend cai no meio da ação, corrida "marcar como vistas" × notificação nova, e spam de curtir/descurtir.
5. Ao final, **desliga o legado inteiro**: `BackEnd/`, MySQL, `infra/mysql/schema.sql`, o projetor `usuario` do `profile_service`,
   o bloco `/api/*` → legado do Caddy e `scripts/dev/run-legacy.*`.
6. Depois disso, **remove o id inteiro temporário de todo o sistema** (`legacy_id`, claim `legacy_uid`, cabeçalho `X-User-Id`):
   todos os serviços e o frontend passam a identificar usuários **só pelo UUID** (D15, §10.1).
7. Notificações **agrupadas** já na v1 ("Ana, Bia e mais 3 curtiram sua publicação") e **retenção de 45 dias** (D6, D13).
8. Estimativa: **≈ 18–21 dias úteis** para uma pessoa (§11).

---

## 2. Avaliação do que existe hoje

### 2.1 Backend legado (único domínio que sobrou nele)

| Rota legada | O que faz | Problemas |
| --- | --- | --- |
| `POST /api/notification` (`{sender, receiver, title, description, category, id_post}`, `sender`/`receiver` como **JSON em string**) | Grava uma notificação | **Forjável:** remetente, destinatário, título e texto vêm do cliente; qualquer logado notifica qualquer um "em nome" de qualquer um. Sem validação de tamanho; `category` inválida → 500; não confere se a postagem existe |
| `GET /api/notification?userId=` | Lista **todas** as notificações do usuário (JOIN `usuario`) | **IDOR:** `userId` vem do cliente (`AuthValidate` só confere que há *alguém* logado): lê as notificações de qualquer pessoa. Sem paginação. Ordena por id `INT` |
| `GET /api/notification?userId=&count=true` | Conta não vistas | Mesmo IDOR |
| `PATCH /api/notification/{id_receiver}` | Marca **todas** como vistas | **IDOR:** marca as de qualquer usuário. Corrida: marca também as que chegaram depois de a lista ser exibida |

Outros pontos: `insertNotification` compara `sender == receiver` mas o resto da regra (dedup, desfazer) não existe; curtir →
descurtir → curtir gera N notificações; excluir a postagem não apaga as notificações (o link vira 404); as notificações dependem da
projeção `usuario` no MySQL, mantida pelo `profile_service` só para isso.

Esquema (`infra/mysql/schema.sql`): `notification(id INT AI, title, description TEXT, id_sender FK, id_receiver FK NULL,
id_category INT, id_post VARCHAR(36), viewed BOOL)` + `usuario` (projeção).

### 2.2 Como o frontend usa o domínio

| Arquivo | Uso |
| --- | --- |
| `services/notificationService.ts` | `listar(idUsuario)`, `contarNaoVistas(idUsuario)`, `marcarComoVistas(idUsuario)`, `notificarCurtida`, `notificarComentario`, `notificarSeguidor` (montam título/texto no cliente) |
| `hooks/useCurtidas.ts` | Após curtir, `notificarCurtida(...).catch(() => undefined)` |
| `hooks/usePostagemActions.ts` | Após responder, `notificarComentario(...)` |
| `hooks/useRelacao.ts` | Após seguir (conta pública), `notificarSeguidor(...)` |
| `hooks/useRelacoesPendentes.ts` | Após **aceitar** uma solicitação, `notificarSeguidor(requerente, eu)` — notifica *quem aceitou* de que "começou a seguir você" |
| `contexts/NotificationsProvider.tsx` | Carrega lista + contagem **uma vez** ao montar (sem atualização até recarregar a página) |
| `pages/Notifications/Notifications.tsx` | Ao abrir: zera o badge; ao sair: `marcarComoVistas` |
| `components/layout/sidebar/NotificationsSidebar.tsx`, `Sidebar.tsx`, `FooterBar.tsx`, `SidebarMenu.tsx` | Lista lateral (desktop) e badge (desktop/mobile) |
| `components/notifications/Notification.tsx` | Ícone por categoria, link para `/perfil/{username}` (FOLLOW) ou `/postagem/{id_post}` |
| `types/api.ts` | `Notification { id:number, sender, receiver, title, description, category: 'LIKE'\|'COMENT'\|'FOLLOW', id_post, viewed }`, `NotificationPayload` |

### 2.3 Acoplamentos com outros domínios

| Quem | Como | Efeito da migração |
| --- | --- | --- |
| **Perfil** | JOIN `usuario` (nome, username, foto do remetente); o `profile_service` mantém essa projeção no MySQL (`LegacyProjectionHandler`, `PROFILE_LEGACY_ENABLED`) | O novo serviço consome `profile.*` (cópia local, como o grafo e o post). **A projeção MySQL e o `LEGACY_DB_*` do perfil saem** |
| **Postagens** | O frontend cria as notificações de curtida e resposta; `id_post` já é UUID | Passa a consumir `post.*` (eventos já publicados) |
| **Grafo** | O frontend cria a notificação de "seguiu" | Passa a consumir `follow.*` (eventos já publicados) |
| **Gateway** | `/api/*` (resto) → legado com `X-User-Id` | Nova rota `/api/notificacoes*`; o bloco do legado sai |

---

## 3. Escopo do `notification_service`

**Dentro:** gerar notificações a partir de eventos (curtida, resposta, novo seguidor, solicitação de seguir, solicitação aceita);
removê-las quando a ação é desfeita ou o conteúdo/conta some; listar paginado; contar não vistas; marcar como vistas; **stream SSE**
com reconexão e reenvio; cópia local de perfis (nome/foto do autor da ação); retenção; limpeza na exclusão de conta.

**Fora (agora):** push do navegador/celular (Web Push, service worker), e-mail de notificação, preferências por tipo ("não me
notifique curtidas"), menções (não existem no domínio de postagens), excluir uma notificação individual, múltiplas instâncias
com fan-out (§7.6).

---

## 4. De onde vêm as notificações (eventos → notificações)

Todos os eventos já existem e já são publicados; o serviço só precisa de filas próprias ligadas a `post.#`, `follow.#` e `profile.#`.

| Evento | Notificação gerada (destinatário ← ator) | Observação |
| --- | --- | --- |
| `post.liked.v1` `{postId, postAuthorId, userId}` | Ator `userId` no grupo `LIKE` de `postId`, para `postAuthorId` | Ignora se `userId == postAuthorId`. Ator repetido não duplica (D5) |
| `post.unliked.v1` | Tira o ator do grupo (D4: sempre, visto ou não); grupo sem atores é apagado | |
| `post.created.v1` com `parentAuthorId != null` | Ator `authorId` no grupo `REPLY` de `parentId`, para `parentAuthorId`, com `resposta = postId` e `trecho = excerpt` | Ignora resposta a si mesmo. **D7: autor privado que o destinatário não segue → não notifica** (consulta a cópia local das arestas) |
| `post.deleted.v1` `{postId}` | Apaga o grupo cujo alvo é `postId` e tira o ator cuja resposta é `postId` | Cobre a cascata (o post_service emite um evento por postagem) |
| `follow.created.v1` `origin=DIRECT` | Ator `followerId` no grupo `FOLLOW` do dia (janela de 24 h), para `followeeId`. Grava a aresta na cópia local | |
| `follow.created.v1` `origin=REQUEST_ACCEPTED` / `AUTO_ON_PUBLIC` | `FOLLOW_ACCEPTED` para `followerId` ← `followeeId` ("aceitou sua solicitação"); **apaga** a `FOLLOW_REQUEST` correspondente; nada para quem aceitou (D3). Grava a aresta | |
| `follow.requested.v1` | `FOLLOW_REQUEST` para `targetId` ← `requesterId`, com botões Aceitar/Recusar (D2) | Nunca agrupa |
| `follow.request-cancelled.v1` / `-rejected.v1` | Apaga a `FOLLOW_REQUEST` | |
| `follow.removed.v1` | Tira o ator do grupo `FOLLOW` (D4) e apaga a aresta local | |
| `profile.created/updated/privacy-changed.v1` | Atualiza `actor_snapshot` (nome, username, foto, privado) | Nome/foto mostrados são sempre os **atuais** |
| `profile.deleted.v1` | Túmulo no snapshot + apaga tudo em que a conta é ator **ou** destinatário + arestas | Mesma lógica de túmulo do grafo/post |

A cópia local das arestas aceitas (`follow_edge`, só para a regra do D7) segue o padrão do `post_service`: só vale a aresta mais nova do
par e `follow.removed.v1` deixa túmulo curto.

Ordem fora de sequência: um `post.liked.v1` que chega antes do `profile.created.v1` do ator gera a notificação normalmente; a
leitura faz `LEFT JOIN` no snapshot e **omite** itens cujo ator ainda não tem cópia ou foi excluído (aparecem quando o perfil chegar).
Eventos de remoção que chegam antes da criação (raro, filas diferentes) deixam um túmulo curto (`notification_removed`, 7 dias) para
a criação atrasada não ressuscitar a notificação — mesmo padrão do `follow_removed` no post_service.

---

## 5. Modelo de dados (`V1__notification.sql`)

Uma notificação exibida é um **grupo** (§13.1); cada pessoa que agiu é um **ator** do grupo. Um ator por linha deixa a deduplicação
(D5) e o "desfazer" (D4) triviais; o grupo guarda o resumo para listar e paginar sem agregar na leitura.

```sql
create table actor_snapshot (            -- cópia pública dos perfis (só o que a notificação mostra); sem id inteiro (D15)
    account_id   uuid primary key,
    username     varchar(60)  not null,
    nome         varchar(120) not null,
    foto_perfil  varchar(255),
    privado      boolean      not null,
    status       varchar(10)  not null,   -- ACTIVE | DELETED (túmulo)
    version      bigint       not null,
    updated_at   timestamptz  not null
);

create sequence notification_activity_seq;   -- ordem global de mudanças: id do evento SSE e base do reenvio

create table notification_group (
    id             uuid primary key,              -- UUIDv7
    recipient_id   uuid        not null,
    type           varchar(20) not null check (type in ('LIKE','REPLY','FOLLOW','FOLLOW_REQUEST','FOLLOW_ACCEPTED')),
    group_key      varchar(120) not null,         -- LIKE:<post> · REPLY:<post> · FOLLOW:<dia UTC> · FOLLOW_REQUEST:<ator> · FOLLOW_ACCEPTED:<ator>
    post_id        uuid,                          -- postagem alvo (LIKE/REPLY)
    actor_count    integer     not null,
    last_activity  timestamptz not null,          -- quando entrou o ator mais recente (ordena a lista)
    activity_seq   bigint      not null,          -- nextval a cada mudança do grupo
    seen_at        timestamptz,                   -- null = não visto; volta a null quando entra ator NOVO
    created_at     timestamptz not null,
    unique (recipient_id, group_key)
);
create index idx_group_feed   on notification_group (recipient_id, last_activity desc, id desc);
create index idx_group_unseen on notification_group (recipient_id) where seen_at is null;
create index idx_group_seq    on notification_group (recipient_id, activity_seq);
create index idx_group_post   on notification_group (post_id);

create table notification_actor (
    group_id     uuid not null references notification_group (id) on delete cascade,
    actor_id     uuid not null,
    reply_id     uuid,                            -- a resposta (REPLY)
    excerpt      varchar(120),                    -- trecho da resposta (REPLY)
    acted_at     timestamptz not null,
    primary key (group_id, actor_id)              -- D5: o mesmo ator não entra duas vezes
);
create index idx_actor_actor on notification_actor (actor_id);
create index idx_actor_reply on notification_actor (reply_id);

create table follow_edge (follower_id uuid, followee_id uuid, relation_id uuid not null, created_at timestamptz not null,
                          primary key (follower_id, followee_id));         -- só para o D7
create table follow_removed (relation_id uuid primary key, removed_at timestamptz not null);   -- túmulos (7 dias)

create table notification_removed (recipient_id uuid, group_key varchar(120), actor_id uuid, removed_at timestamptz not null,
                                   primary key (recipient_id, group_key, actor_id));   -- túmulos de "desfazer" (7 dias)

-- biblioteca de eventos
create table outbox_event (...);       -- exigido pelo relay mesmo sem publicar nada (D12)
create table processed_message (...);
```

O título/texto **não** é guardado: o frontend formata a frase a partir de `tipo` (**D8**). Isso evita texto vindo de terceiros e
deixa a mensagem mudar sem migração.

Todas as escritas num grupo travam a linha do grupo (`select … for update`, ou o `insert … on conflict` do próprio grupo), para dois
atores chegando juntos não perderem `actor_count`. Os contadores são conferidos pela rotina de manutenção, como nos outros serviços.

---

## 6. API REST (`/api/notificacoes`, token de usuário, identidade só do `sub`)

| Rota | Sucesso | Erros |
| --- | --- | --- |
| `GET /api/notificacoes?cursor&limite` | 200 `{ itens, proximoCursor }` (grupos por `last_activity` desc; cursor = `last_activity` + id; `limite` 1–50, padrão 20) | 400 `parametro_invalido` |
| `GET /api/notificacoes/nao-vistas` | 200 `{ total }` (grupos não vistos, limitado a 99 → o frontend mostra "99+") | — |
| `POST /api/notificacoes/vistas` `{ ate: <seq> }` | 204. Marca como vistos os grupos do usuário com `activity_seq <= ate` (o maior `seq` que a tela exibiu; o que mudou depois continua novo). Empurra `contagem` para as outras abas | 400 |
| `GET /api/notificacoes/stream` | 200 `text/event-stream` (§7) | 401; 429 `muitas_conexoes` |

Não há `POST` de criação nem nenhuma rota que receba id de usuário. Os métodos não seguros passam pelo `OriginCheckFilter` (CSRF), como
nos outros serviços.

**Notificação (grupo):**
```json
{
  "id": "0193a1b2-…",
  "seq": 4182,
  "tipo": "REPLY",
  "atores": [
    { "id": "0192…", "username": "ana", "nome": "Ana", "foto_perfil": "av_….jpg", "foto_url": "/media/avatars/av_….jpg" },
    { "id": "0192…", "username": "bia", "nome": "Bia", "foto_perfil": null, "foto_url": null }
  ],
  "totalAtores": 5,
  "postagem": "0192f3c4-…",
  "resposta": "0193a1b0-…",
  "trecho": "concordo demais!",
  "atualizada_em": "2026-11-15T14:03:22.123Z",
  "vista": false
}
```
- `atores`: os **2 mais recentes** (para "Ana, Bia e mais 3…"); `totalAtores` é o total do grupo. `id` do usuário é o **UUID** (D15).
- `resposta`/`trecho`: da resposta mais recente (só `REPLY`). `postagem`, `resposta` e `trecho` são `null` quando não se aplicam.
- `FOLLOW_REQUEST` e `FOLLOW_ACCEPTED` têm sempre um ator. Os botões Aceitar/Recusar do `FOLLOW_REQUEST` chamam as rotas do grafo
  (`POST /api/relacoes/solicitacoes/{usuario}/aceitar`, `DELETE /api/relacoes/solicitacoes/{usuario}`); a notificação some pelo
  evento resultante (e o frontend já a tira da tela na hora, de forma otimista).

---

## 7. Tempo real por SSE

### 7.1 Por que SSE (e não WebSocket)

O fluxo é **só servidor → navegador**; as ações (marcar como vistas) continuam em REST. SSE é HTTP comum: passa pelo Caddy e pelo
`forward_auth` sem configuração especial, leva o cookie da própria origem automaticamente, reconecta sozinho e tem `Last-Event-ID`
nativo. No Spring MVC é `SseEmitter` (servlet assíncrono: a conexão aberta **não** prende uma thread do Tomcat). Nenhuma dependência nova.

### 7.2 Protocolo

```
retry: 5000

event: contagem                       ← sempre logo ao conectar (ressincroniza o badge)
data: {"naoVistas":3}

id: 4182                              ← id = activity_seq do grupo (cresce a cada mudança)
event: notificacao                    ← grupo novo OU atualizado (o frontend substitui pelo `id` do grupo e sobe ao topo)
data: { ...mesmo JSON do §6... }

event: removida                       ← curtida desfeita, postagem excluída…
data: {"id":"0193a1b2-…"}

event: contagem                       ← depois de "vistas" em outra aba
data: {"naoVistas":0}

: ping                                ← comentário a cada 25 s (mantém proxies e NAT abertos)
```

- **Reenvio:** ao reconectar, o cliente manda o último `seq` recebido (`Last-Event-ID` ou `?desde=`); o servidor envia os grupos do
  usuário com `activity_seq >` esse valor (no máximo 50; se houver mais, só a `contagem` e o frontend recarrega a lista). Remoções perdidas não são reenviadas; a `contagem`
  inicial e a próxima listagem corrigem.
- **Entrega depois do commit:** o handler do evento grava a notificação; um `TransactionSynchronization.afterCommit` publica no
  registro em memória, que escreve nos emitters daquele `recipient_id`. Nada é empurrado se a transação falhar.

### 7.3 Autenticação, revogação e sessão deslizante

O ponto mais delicado. Fatos do que já existe (verificados no `authentication_service` e no frontend):

- O token de acesso vive **2 h** e fica no cookie `at` (`HttpOnly`, `SameSite=Lax`, `Path=/`) com `Max-Age` = tempo restante do
  token: **depois do `exp` o navegador nem envia o cookie**.
- A **revogação** (logout geral, troca de senha, banimento, exclusão) é um registro **em memória do auth** (`RevocationRegistry`,
  versão mínima `ver` por conta). Só o auth sabe disso; quem aplica é o `forward_auth` do gateway.
- A **sessão deslizante** só renova se o usuário **agir** entre `exp − 10 min` e `exp + 5 min` (interceptor do axios →
  `POST /auth/refresh`). Ocioso além disso, volta ao login.
- O `EventSource` **não envia cabeçalhos próprios** e, quando o servidor responde algo diferente de 200 (ex.: 401), ele **desiste
  sem dizer o status** (só dispara `error`).

Problemas que isso cria e como cada um é tratado:

| Problema | Tratamento |
| --- | --- |
| O `forward_auth` só confere o token **ao abrir** a conexão; um stream aberto continuaria recebendo depois de um logout geral | O servidor **encerra o stream** em `prazo = min(exp do JWT, agora + NOTIF_SSE_MAX_DURATION ± 1 min)` (15 min, **D9**). A reconexão passa de novo pelo `forward_auth` → o token revogado perde o tempo real em **≤ 15 min** |
| Um stream não pode durar mais que o token | O `exp` entra no prazo acima: nenhum stream passa do vencimento |
| O stream **não pode renovar a sessão** (senão uma aba esquecida manteria o login por 7 dias) | Reconexões e a sonda de sessão usam `skipSessionRefresh: true`; nada do stream chama `/auth/refresh` |
| O 401 da reconexão não é visível no `EventSource` | O frontend controla a reconexão (desliga a nativa) e usa uma **sonda** REST para descobrir o motivo |
| Ao recriar o `EventSource`, o `Last-Event-ID` nativo se perde | O cliente guarda o último `id` recebido e reconecta com `?desde=<id>`; o servidor aceita o cabeçalho `Last-Event-ID` **ou** `desde` |
| Conta excluída com stream aberto | O serviço já consome `profile.deleted.v1`: fecha na hora os streams daquela conta |

**Servidor (`notification_service`):**

1. O controller lê `sub` e `exp` do `Jwt` já validado pelo resource server, calcula o `prazo` e cria o `SseEmitter` com timeout um
   pouco maior que o prazo (rede de segurança).
2. Registra o emitter em `recipient_id → {emitters}` e envia: `retry`, a `contagem` atual e, se veio `desde`/`Last-Event-ID`, as
   notificações posteriores (até 50).
3. Agenda o encerramento: no `prazo` envia `event: encerrado` `data: {"motivo":"renovar"}` e completa o emitter. O evento
   explícito separa o **fim planejado** (reconectar já) de uma **queda** (reconectar com backoff).
4. Heartbeat `: ping` a cada 25 s; falha de escrita remove o emitter.

**Cliente (`lib/notificacoesStream.ts`) — máquina de estados:**

```
            login / provider monta
                    │
                    ▼
  ┌──────────► CONECTANDO ──200──► ABERTO ──"encerrado"──► (jitter 0–2 s) ──┐
  │                 │                 │                                     │
  │              erro/HTTP≠200     erro (queda)                             │
  │                 └───────┬─────────┘                                     │
  │                         ▼                                               │
  │               SONDA: GET /api/notificacoes/nao-vistas                   │
  │                   (skipSessionRefresh: true)                            │
  │          200 ───────────┼──────── 401 ──────────── rede/5xx             │
  │      (atualiza badge)   │           │                 │                 │
  │                         │           ▼                 ▼                 │
  │                         │   AGUARDANDO_ATIVIDADE    BACKOFF             │
  │                         │   (não renova a sessão)   1,2,5,10…60 s       │
  │                         │           │                 │                 │
  │                         │   próxima resposta OK do    │                 │
  │                         │   axios (o usuário agiu)    │                 │
  └─────────────────────────┴───────────┴─────────────────┴─────────────────┘
                logout / sessão encerrada → DESLIGADO (fecha o EventSource)
```

- `lib/api.ts` ganha um ouvinte de "resposta bem-sucedida" (`onApiSucesso`) que tira o stream de `AGUARDANDO_ATIVIDADE`: se o
  usuário agiu, o interceptor já renovou a sessão (ou ela ainda valia) e o novo cookie está no navegador.
- Os cookies são do navegador, não da aba: se o usuário age em **outra** aba, o cookie novo vale para todas. Com a aba líder
  (**D10**), a atividade de qualquer aba é avisada pelo `BroadcastChannel`.
- No logout, o provider fecha o stream **antes** de chamar `/auth/logout`.

**Linhas do tempo** (token emitido 10:00, `exp` 12:00, janela de renovação a partir de 11:50):

| Cenário | O que acontece |
| --- | --- |
| Usuário ativo | Streams de ~15 min se renovam sozinhos (10:15, 10:30…). Às 11:52 ele clica → refresh → `exp` 13:52. O stream aberto às 11:45 tinha prazo 12:00 → `encerrado` → reconecta com o cookie novo. Sem interrupção visível |
| Usuário ocioso | Streams continuam até 11:45 (token ainda válido, **sem renovar**). Às 12:00 `encerrado` → reconexão sem cookie → 401 → sonda 401 → `AGUARDANDO_ATIVIDADE`. Volta 12:03 e clica → refresh (dentro da tolerância de 5 min) → stream religa e a `contagem` corrige o badge. Volta 12:30 → refresh falha → login, como hoje |
| Logout geral às 10:20 (outro aparelho) | O stream aberto às 10:15 vai até ~10:30 (**janela máxima de exposição: D9**). Reconexão → `forward_auth` 401 → sonda 401 → aguarda; a próxima ação tenta o refresh, que falha (refresh revogado) → login |
| Conta excluída | `profile.deleted.v1` → streams fechados na hora |
| Serviço reiniciado | `@PreDestroy` completa os emitters → erro → sonda 503/rede → backoff → volta e reenvia com `desde` |

Alternativa para revogação imediata (fora do escopo, muda contrato do auth): o auth publicaria `account.tokens-revoked.v1
{accountId, minVersion}` e o serviço fecharia os streams cujo `ver` é menor. Ver **D9**.

### 7.4 Limites e recursos

| Item | Proposta |
| --- | --- |
| Streams por usuário | `NOTIF_SSE_MAX_PER_USER` = 5 (o 6º → 429 `muitas_conexoes`, ou fecha o mais antigo: **D11**) |
| Streams por instância | `NOTIF_SSE_MAX_TOTAL` = 5.000 → 503 `servidor_ocupado` |
| Escrita lenta/cliente morto | Falha de escrita ou timeout remove o emitter; o heartbeat detecta conexões mortas em ≤ 25 s |
| Spring Security + async | O `SseEmitter` gera *async dispatch*: liberar `DispatcherType.ASYNC`/`ERROR` na cadeia, senão o stream termina com erro 401/403 depois de aberto (problema conhecido) |
| Desligamento | `@PreDestroy` completa todos os emitters (os navegadores reconectam em outra instância/depois do deploy) |

**Limite de 6 conexões HTTP/1.1 por origem no navegador:** em desenvolvimento o gateway (`:8080`) e o Vite (`:5173`) falam
HTTP/1.1; cada aba aberta prende 1 das 6 conexões com o SSE. Com 6 abas, a 7ª trava. Em produção o Caddy com HTTPS fala HTTP/2
(sem esse limite). Opções em **D10**: aceitar e documentar, ou uma **aba líder** (Web Locks API + `BroadcastChannel`: só uma aba
abre o stream e repassa para as outras; ~80 linhas no frontend, sem dependência).

### 7.5 Gateway e proxy de desenvolvimento

- **Caddy:** novo bloco `@notifications path /api/notificacoes /api/notificacoes/*` igual ao do grafo/post (cookie → Bearer +
  `forward_auth`). O `reverse_proxy` do Caddy já faz *flush* imediato de `text/event-stream` e não tem timeout de leitura por padrão;
  o serviço manda `X-Accel-Buffering: no` e `Cache-Control: no-cache` por garantia. **Não** usar `encode` (compressão) nessa rota.
- **Vite (`npm run dev`):** o proxy (http-proxy) repassa streams; verificar na Fase 6 que nada bufferiza.
- **Docker compose:** novo serviço `notification_service` (perfil `apps`), `notif_db_init` para o banco, sem porta publicada além da
  de desenvolvimento, `gateway_host` apontando `NOTIF_UPSTREAM` para `host.docker.internal:8085`.

### 7.6 Escala

Registro de emitters **em memória** (uma instância, como os rate limits atuais). Para várias instâncias, cada uma declararia uma fila
exclusiva/anônima ligada a uma exchange `fanout` de "notificação pronta" e entregaria só aos seus emitters. Fica atrás de uma
interface (`NotificationPush`) para trocar sem mexer no resto (**D10**/futuro).

---

## 8. Regras

| Regra | Detalhe |
| --- | --- |
| Identidade | Só do `sub`. Nenhuma rota recebe usuário; `ate` só limita as do próprio usuário |
| Auto-notificação | Nunca (ator = destinatário → ignorado) |
| Agrupamento (D6) | Regras do §13.1. Ator **novo** num grupo: sobe ao topo, volta a "não visto", empurra o grupo pelo SSE |
| Dedup (D5) | PK `(group_id, actor_id)`: o mesmo ator repetindo a ação não entra de novo nem muda o grupo |
| Desfazer (D4) | Descurtir, deixar de seguir, cancelar/recusar solicitação, excluir postagem → tira o ator (vista ou não). Grupo ainda com atores → empurra o grupo atualizado; grupo vazio → apaga e empurra `removida`. Sempre `contagem` |
| Solicitação (D2/D3) | `FOLLOW_REQUEST` sai ao aceitar, recusar ou cancelar; ao aceitar, o solicitante recebe `FOLLOW_ACCEPTED` e quem aceitou não recebe nada |
| Resposta privada (D7) | Autor da resposta com conta privada que o destinatário não segue → **não notifica** |
| Conta excluída | Apaga grupos do destinatário e tira a conta de todos os grupos como ator; túmulo no snapshot |
| Retenção (D13) | Rotina agendada apaga grupos com `last_activity` mais antiga que `NOTIF_RETENTION` (**45 dias**). `FOLLOW_REQUEST` **não expira por idade**: só sai ao ser aceita, recusada ou cancelada. Sem limite de quantidade por usuário |
| Datas | `timestamptz`, ISO-8601 UTC na API |
| Consumo | Inbox idempotente; tipos desconhecidos e `aggregateVersion` antigos ignorados; falha → retry → DLQ (mesma config do post) |

---

## 9. Frontend

| Arquivo | Mudança |
| --- | --- |
| `services/notificationService.ts` | Reescrito: `listar(cursor)`, `contarNaoVistas()`, `marcarComoVistas(ate)`; **remove** `notificarCurtida/Comentario/Seguidor` e `enviar` |
| `hooks/useCurtidas.ts`, `usePostagemActions.ts`, `useRelacao.ts`, `useRelacoesPendentes.ts` | Removem as chamadas `notificar*` (o backend gera) |
| `lib/notificacoesStream.ts` (novo) | Abre/fecha o `EventSource`, backoff, checagem de sessão antes de reconectar (§7.3), parse tipado (`unknown` + narrowing) |
| `contexts/NotificationsProvider.tsx` | Lista paginada + contagem + stream: insere no topo ao receber `notificacao`, remove em `removida`, atualiza o badge em `contagem`; para o stream no logout |
| `lib/api.ts` | Expor um "ouvinte de sucesso" (ou evento) para religar o stream quando o usuário voltar a agir |
| `pages/Notifications/Notifications.tsx`, `NotificationsSidebar.tsx` | "Carregar mais"; marcar como vistas com `ate = maior seq exibido` |
| `components/notifications/Notification.tsx` | Frase agrupada por `tipo` ("Ana, Bia e mais 3…"), avatares empilhados, ícone; `trecho` em linha secundária; data relativa. `FOLLOW_REQUEST` com botões **Aceitar/Recusar** (reaproveita `relacaoService`; some na hora, de forma otimista); `FOLLOW_ACCEPTED` → perfil |
| `lib/abaLider.ts` (novo, D10) | Web Locks elege a aba que abre o stream; `BroadcastChannel` repassa os eventos e a "atividade do usuário" às outras abas; se a líder fecha, outra assume |
| `types/api.ts` | Novo `Notificacao`/`NotificacaoTipo`, remove `NotificationPayload` e o `Notification` antigo |
| Toast ao receber (D14) | Toast discreto quando chega notificação e o usuário **não** está na página de notificações nem com a lista lateral aberta |
| `docs/COMPONENTS.md`, `docs/ARCHITECTURE.md` do frontend | Atualizados |

---

## 10. Desligamento do legado (última fase)

| Onde | O que sai |
| --- | --- |
| `BackEnd/` | **A pasta inteira** (inclusive o `.git` aninhado, o `application.properties` com segredos antigos e o `logs/app.log` versionados). Os segredos já são tratados como vazados |
| `infra/mysql/schema.sql`, serviço `mysql` e volume `mysql_data` no compose | Saem (o volume só é apagado com sua autorização) |
| `profile_service` | `LegacyProjectionHandler`, `PROFILE_LEGACY_ENABLED`, `LEGACY_DB_*`, driver MySQL do `pom.xml`, dependência do `mysql` no compose |
| `infra/caddy/Caddyfile` | Bloco `handle /api/*` → legado (vira 404) e o `copy_headers X-User-Id X-Account-Id` |
| `scripts/dev/run-legacy.*`, `.env.example` (`LEGACY_*`, `SQLDB_*`, `MYSQL_*`), `docs/rodando-localmente.md`, `docs/ARCHITECTURE.md`, `README.md`, `CLAUDE.md` | Atualizados |
| `copy_headers X-User-Id X-Account-Id` do Caddy | Sai junto (o legado era o único consumidor) |

### 10.1 Remoção do id inteiro temporário (D15) — fase seguinte

Sem dados reais, todo o sistema passa a usar **só o UUID**. Nos JSON públicos, o campo `id` do usuário **passa a ser o UUID** (string)
e o `accountId` deixa de existir (era o mesmo valor). Esquema sempre por **migrações novas** (`V<n>__remove_legacy_id.sql`), nunca
editando as existentes.

| Onde | O que muda |
| --- | --- |
| `authentication_service` | Tira a claim `legacy_uid` do token (`TokenService`), o `legacyUserId` de `Account`/`AuthenticatedAccount`/`BearerAuthenticationFilter`, o `X-User-Id` do `/internal/auth/verify` (fica só `X-Account-Id`) e o `legacyId` de `ProfileClient`/`ProfileCreated`; migração remove a coluna |
| `profile_service` | Tira `legacy_id` de `Profile`, a sequence `legacy_id_seq`, `byLegacyId`, o `legacyId` da resposta de `PUT /internal/profiles/{uuid}`, das DTOs e dos eventos (§13.2); rotas `{usuario}` aceitam só UUID ou username; migração remove coluna e sequence |
| `social_graph_service` | `{usuario}` só UUID; tira `legacy_id` de `UserSnapshot`/`SnapshotRepository`/`UserRef`/DTOs; migração |
| `post_service` | `autor` só UUID; tira `legacy_id` de `AuthorSnapshot`/`AuthorRepository`/`UserRef`/DTOs; migração |
| `notification_service` | Já nasce sem o id inteiro |
| Frontend | `Usuario.id: string` (e `UsuarioResumo`), remove `accountId`; o `tsc` estrito aponta todos os ~50 usos (comparações, chaves de lista, chamadas a services) |
| Docs | READMEs/ARCHITECTURE dos serviços, `CLAUDE.md` (parágrafo do "id inteiro temporário") e `docs/ARCHITECTURE.md` |
| Testes | Todos os serviços (`mvn -pl <serviço> -am test`) |

Ordem para nunca quebrar no meio: (1) consumidores param de **ler** `legacyId` dos eventos; (2) serviços e frontend passam a usar só
UUID; (3) o profile para de **enviar** `legacyId` e o auth tira a claim e o cabeçalho; (4) migrações que removem as colunas.

---

## 11. Fases e estimativa

| Fase | Entrega | Dias |
| --- | --- | --- |
| 1 | Esqueleto: `pom`, config tipada, segurança (JWT + `OriginCheckFilter`), `V1__notification.sql`, health, testes base | 1,5 |
| 2 | Consumidores: `profile.*` (snapshot + túmulo + limpeza), `follow.*` (arestas + notificações), `post.*` → grupos e atores (§4, §8, §13.1); testes por evento (fora de ordem, duplicados, dedup, auto-notificação, D7, concorrência no grupo) | 4,5 |
| 3 | API REST: listar grupos (cursor), contar, marcar como vistos `ate`; retenção de 45 dias; testes de autorização | 1,5 |
| 4 | SSE: registro, emitters, heartbeat, duração máxima/`exp`, reenvio por `seq`, `afterCommit`, limites (fecha o mais antigo), async + Security; testes (MockMvc async + cliente SSE real no teste de integração) | 2,5 |
| 5 | Infra: compose (`notif_db_init`, serviço), Caddyfile, `scripts/dev/run-notification.*`, `.env.example`, README + ARCHITECTURE | 1 |
| 6 | Frontend: service, stream, aba líder, provider, componentes agrupados, botões de solicitação, toast, remoção do `notificar*`, verificação no navegador | 3–4 |
| 7 | Desligar o legado (§10) e docs gerais | 1–2 |
| 8 | Remover o id inteiro temporário de todos os serviços e do frontend (§10.1) | 3–4 |
| | **Total** | **≈ 18–21** |

Commits/branches no padrão dos domínios anteriores (branches locais empilhados): `docs(notificacoes): adiciona plano…`,
`feat(notificacoes): adiciona o notification_service`, `build(infra): liga o notification_service…`,
`feat(frontend): usa o notification_service com SSE`, `refactor(legado): remove o backend legado e o MySQL`,
`refactor: usa só o UUID para identificar usuários` (um commit por serviço + frontend).

---

## 12. Riscos

| Risco | Mitigação |
| --- | --- |
| Stream aberto depois de revogação | Duração máxima + fechamento no `exp` (§7.3) |
| Aba ociosa mantendo a sessão viva | Stream não renova; reconexão com `skipSessionRefresh` |
| Limite de 6 conexões HTTP/1.1 em dev | D10 |
| Buffer em algum proxy (Vite, Caddy com `encode`) | Cabeçalhos anti-buffer + teste manual na Fase 6 |
| Eventos fora de ordem entre filas | Túmulos curtos + `LEFT JOIN` no snapshot |
| Perda de push entre queda e reconexão | `Last-Event-ID` + `contagem` ao conectar |
| Nada testado contra RabbitMQ/Docker reais (como nos serviços anteriores) | Teste manual completo na Fase 5/6 |

---

## 13. Decisões

Fechadas em 2026-11-16 pelo responsável do projeto, exceto onde indicado.

| # | Decisão | Escolha |
| --- | --- | --- |
| **D1** | Nome, porta e rota | ✅ `notification_service`, porta 8085, banco `notification`, rota `/api/notificacoes` |
| **D2** | Solicitação de seguir | ✅ Notifica `FOLLOW_REQUEST` ("X pediu para seguir você") **com botões Aceitar/Recusar na própria notificação** (chamam o `social_graph_service`); a notificação some ao aceitar, recusar ou cancelar |
| **D3** | Solicitação aceita | ✅ `FOLLOW_ACCEPTED` só para o solicitante; nada para quem aceitou |
| **D4** | Desfazer a ação | ✅ Remove a notificação sempre (vista ou não) |
| **D5** | Repetir a mesma ação | ✅ Mantém só uma (dedup); não volta a "não vista" |
| **D6** | Agrupar | ✅ Agrupar já na v1, com as regras do §13.1 |
| **D7** | Resposta de conta privada que o destinatário não segue | ✅ Não notifica (exige a cópia local das arestas aceitas de `follow.*`) |
| **D8** | Contrato da API | ✅ Novo formato (`tipo`, `ator(es)`, `trecho`, `vista`, ids UUID; frase montada no frontend) |
| **D9** | Duração máxima do stream | ✅ 15 min (±1 min) |
| **D10** | Várias abas | ✅ Aba líder: 1 stream por navegador (Web Locks + `BroadcastChannel`) |
| **D11** | Exceder streams por usuário | ✅ Fecha o mais antigo |
| **D12** | Publicar eventos | ✅ Não; o serviço só consome |
| **D13** | Retenção | ✅ 45 dias, sem limite de quantidade; `FOLLOW_REQUEST` não expira por idade e sai ao ser aceita/recusada/cancelada |
| **D14** | Toast | ✅ Toast quando o usuário não está na área de notificações |
| **D15** | `legacy_id` / `X-User-Id` / `legacy_uid` | ✅ Remover tudo agora (Fase 8, depois do desligamento do legado): só UUID em todos os serviços e no frontend (§10.1). Aprovada a mudança na autenticação e nos contratos |
| **D16** | Ordem da entrega | ✅ Serviço + infra + frontend com SSE; desligar o legado só depois |

### 13.1 Agrupamento (D6) — fechado

| Tipo | Agrupa por | Exibição |
| --- | --- | --- |
| `LIKE` | mesma postagem | "Ana, Bia e mais 3 curtiram sua publicação" |
| `REPLY` | mesma postagem | "Ana e mais 2 responderam sua publicação" (trecho da mais recente) |
| `FOLLOW` | dia (UTC) | "Ana e mais 4 começaram a seguir você" |
| `FOLLOW_REQUEST` | não agrupa | um item por pessoa, com Aceitar/Recusar; some ao aceitar/recusar/cancelar |
| `FOLLOW_ACCEPTED` | não agrupa | um item por pessoa |

Modelo no §5. Um grupo sobe para o topo e volta a "não visto" quando entra um ator **novo**; se o último ator sai, o grupo é apagado.
No SSE, cada mudança reenvia o grupo inteiro (o frontend substitui pelo `id`).

### 13.2 `legacyId` nos eventos `profile.*.v1` — fechado

O `CLAUDE.md` manda que mudança que quebra contrato vire uma versão nova (`.v2`). **Exceção aprovada (2026-11-16):** o campo `legacyId`
sai da própria `.v1`, sem `.v2`, porque não há dados reais nem instância implantada e todos os consumidores estão neste repositório.
A ordem do §10.1 é obrigatória: os consumidores param de **ler** o campo antes de o profile parar de **enviar**. A exceção fica
registrada no README do `profile_service` (seção de eventos).

## 14. Testes (padrão dos serviços atuais: PostgreSQL embutido, JWKS local, sem Docker)

- **Eventos:** cada linha do §4; duplicados (inbox); fora de ordem (remoção antes da criação, ator sem snapshot); auto-notificação;
  dedup; `post.deleted.v1` em cascata; `profile.deleted.v1` como ator e como destinatário; versões antigas ignoradas.
- **API:** só vê as próprias; cursor estável com inserções concorrentes; `vistas` com `ate` não marca as posteriores; 401 sem token;
  403 de origem inválida no `POST`.
- **SSE:** recebe `contagem` ao conectar; recebe `notificacao` após o commit e **não** recebe se a transação falhar; `removida`;
  `Last-Event-ID` reenvia só as posteriores; fecha no `exp`/duração máxima; limite por usuário; outro usuário nunca recebe.
- **Manual (Docker):** dois navegadores, curtir/responder/seguir e ver chegar sem recarregar; derrubar e subir o serviço (reconexão);
  logout geral com o stream aberto (fecha em ≤ D9); aba ociosa até o vencimento (não renova a sessão).

---

## 15. Estado da implementação (2026-11-16)

| Fase | O que foi feito | Onde |
| --- | --- | --- |
| 1–4 | `notification_service`: esqueleto, segurança, migração `V1`, consumidores `profile.*`/`follow.*`/`post.*`, grupos e atores, API REST, SSE (fila por conexão, prazo, reenvio por `seq`, fecha o mais antigo) | `notification_service/` — 54 testes (eventos, API, SSE com cliente real) |
| 5 | Compose (`notification_db_init`, `notification_service`), Caddyfile (`flush_interval -1`), scripts `run-notification`, variáveis `NOTIFICATION_*` no `.env.example` e no `.env` local | `docker-compose.yml`, `infra/caddy/Caddyfile`, `scripts/dev/` |
| 6 | Frontend: lista agrupada e paginada, Aceitar/Recusar na notificação, toast (D14), stream com reconexão e sonda sem renovar a sessão, aba líder (D10) | `FrontEnd/src/lib/notificacoes*.ts`, `contexts/NotificationsProvider.tsx` |
| 7 | Legado desligado: `BackEnd/` fora do git (pasta local ignorada), MySQL, `legacy_backend`, `run-legacy`, projeção `usuario` do `profile_service`; `/api/*` sem dono responde 404 em JSON | compose, Caddyfile, `profile_service` |
| 8 | Só UUID: `legacy_id`/`legacy_user_id`/`legacy_uid`/`X-User-Id` removidos de auth, profile, grafo e posts (migrações `V2`/`V3`); `Usuario.id` é o UUID no frontend; `accountId` saiu do JSON | todos os serviços e `FrontEnd/` |

**Diferenças em relação ao plano:** a tabela `notification_removed` (túmulos de "desfazer") não foi criada: `post.*` e `follow.*`
chegam em ordem dentro da sua fila e o perfil ausente é tratado na leitura; `follow_removed` (túmulos de vínculos) existe. A
exceção de contrato da §13.2 (tirar `legacyId` da `.v1`) foi aplicada na ordem do §10.1.

**Pendências / não verificado:**
- Nada rodou com **Docker** (daemon desligado durante o trabalho): `docker compose config` valida a sintaxe, mas a subida real, o
  `Caddyfile` (nunca passou por um binário do Caddy), as filas no RabbitMQ real e o fluxo no navegador (SSE pelo gateway e pelo
  proxy do Vite, aba líder entre várias abas, toast) **não foram exercitados**.
- Os testes cobrem os serviços (PostgreSQL embutido) e o frontend só passa por `typecheck`, `lint` e `build` (sem runner de testes).
- O `.env` local ganhou as variáveis `NOTIFICATION_*`; as `LEGACY_*`/`MYSQL_*` antigas ficam sem uso (podem ser apagadas).
- A pasta local `BackEnd/` ainda existe (ignorada pelo git, com um `.git` aninhado antigo): pode ser apagada à mão. O volume Docker
  `social-flux_mysql_data` também ficou: só se apaga com `docker volume rm` (decisão do dono do projeto).
- A dependência `concurrently` do `FrontEnd/package.json` ficou sem uso (os scripts `start*` saíram).
