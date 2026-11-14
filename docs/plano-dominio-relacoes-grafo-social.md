# Plano — Domínio Relações: `social_graph_service` (grafo social: seguir, seguidores e solicitações)

Data: 2026-11-12 · Complementa `analise-backend-e-migracao-spring-boot.md` e `plano-dominio-perfil-servico-proprio.md`
(decisão D11 daquele plano: “Perfil agora; Relações logo em seguida”).

> **Estado (2026-11-12): decisões D1–D14 fechadas pelo dono do projeto (ver §13).** D10: API interna de visibilidade fica
> fora desta entrega. D13: o código de eventos (outbox/inbox/AMQP) é **extraído agora** para um módulo compartilhado
> (Fase 0.5, §11.1), antes do serviço novo. Resumo: PostgreSQL na instância existente, tabelas `follow` e
> `follow_request` separadas, novo contrato `/api/relacoes/*` paginado, cópia local por eventos, pendentes aceitas ao virar
> público, “em comum” visível a qualquer logado, remover seguidor incluído, limites antiabuso com os valores recomendados
> (configuráveis), sem token de serviço por enquanto, seguir idempotente, notificação de seguir continua pelo frontend.
>
> **Implementação (2026-11-13): Fases 0.5–4 prontas** — biblioteca `libs/socialflux-events` e o serviço `social_graph_service/`
> (rotas, regras, cópia local por eventos, eventos `follow.*`, limites, projeção MySQL por estado), com testes de integração
> contra PostgreSQL real. **Infra ligada (2026-11-14, aprovada):** serviço e `graph_db_init` no `docker-compose.yml`, rota
> `/api/relacoes*` no `Caddyfile`. **Frontend no novo contrato (Fase 5, 2026-11-14): pronto.** **Pendente:** remoção de Relações do legado (Fase 6). Base: leitura de `BackEnd/`
> (`RelacaoController`, `RelacaoService`, `RelacaoDao`, `UsuarioDao.buscarRelacoesEmComum`, `PostagemDao` — feed com JOIN em
> `relacao`, `App`), de `infra/` (`schema.sql`, `Caddyfile`), do `docker-compose.yml`, do `profile_service/` (README, eventos,
> outbox/inbox, projeção legada, migração `V1__profile.sql`), do `authentication_service/` (`ServiceTokenIssuer`, `ProfileClient`)
> e do frontend (`relacaoService.ts`, `useRelacao`, `useRelacoesPendentes`, `ActionsProfile`, `Perfil.tsx`, `types/api.ts`).
> Premissa herdada do plano de perfil: **não existem dados reais em nenhum banco** (sem importação, sem cutover com congelamento).
> As decisões que dependem de você estão em **§13 (D1–D14)**.

---

## 1. Resumo executivo

1. Criar o **`social_graph_service`** (Spring Boot 4 · Java 21 · PostgreSQL, banco próprio `social_graph`), no mesmo padrão do
   `profile_service`: resource server JWT, Flyway, outbox/inbox com RabbitMQ, erros `{ mensagem, sucesso, codigo }`.
2. Ele passa a ser o **dono do grafo social**: quem segue quem, solicitações pendentes para contas privadas, contagens,
   seguidores em comum e a regra “*este usuário pode ver o conteúdo daquele?*” para os demais domínios.
3. **Banco: PostgreSQL (SQL relacional, lista de adjacência)** — análise em §4. O grafo do produto é de **1 a 2 saltos** (segue?
   lista, contagem, em comum), com **máquina de estados transacional** (solicitado → aceito) e **outbox na mesma transação**
   (regra do projeto). Isso favorece um banco ACID. Bancos de grafo (Neo4j) e wide-column (Cassandra) só compensam em cenários
   que o produto ainda não tem; ficam como *read models* opcionais no futuro, alimentados pelos eventos.
4. Durante a transição, o serviço **projeta as arestas aceitas na tabela `relacao` do MySQL** (como o `profile_service` faz com
   `usuario`), para o feed do legado (`PostagemDao`) continuar funcionando sem alteração.
5. Corrige no domínio todas as falhas do legado: IDOR em criar/aceitar/remover, `privado` vindo do cliente (SEC-03), seguir a si
   mesmo (LOG-07), grafo de contas privadas exposto, respostas 200 vazias, ausência de paginação.
6. Estimativa: **≈ 15–19 dias úteis** para uma pessoa (§11).

---

## 2. Avaliação do que existe hoje

### 2.1 Domínio Relações no backend legado

| Rota legada | O que faz | Problemas |
| --- | --- | --- |
| `POST /api/relacao` | Corpo `{ user, profileUser }` com os **objetos inteiros** dos dois usuários; `aceito = !profileUser.privado` | **IDOR** (o seguidor vem do corpo: qualquer um faz qualquer usuário seguir qualquer outro); `privado` **do cliente** → basta mandar `privado:false` para entrar direto numa conta privada (SEC-03); seguir a si mesmo é permitido (LOG-07); checagem de duplicidade não atômica (o `UNIQUE` do `schema.sql` salva o dado, mas vira 500); usuário inexistente → 500 |
| `GET /api/relacao?id_seguidor=&id_seguido=` | Relação entre dois usuários | Responde **200 com corpo vazio** quando não existe; consulta a relação de quaisquer terceiros |
| `GET /api/relacao?id_seguido=` / `?id_seguidor=` | Seguidores / seguidos de qualquer usuário | **Expõe o grafo de contas privadas** a qualquer logado; lista **inteira, sem paginação**; o frontend conta seguidores pelo tamanho da lista |
| `GET /api/relacao?id_seguido=&pending=true` | Solicitações pendentes | Qualquer logado lê as solicitações **de qualquer usuário** |
| `PATCH /api/relacao/{id}` | Aceita a solicitação `{id}` | **Qualquer logado aceita qualquer solicitação**; não confere se está pendente nem se quem chama é o seguido; id inexistente → 200 |
| `DELETE /api/relacao/{id}` | Remove relação (deixar de seguir, cancelar ou recusar) | **Qualquer logado remove qualquer relação**; id inexistente → 200 |
| `GET /api/relacao/compartilhado?id_usuario=&id_usuario_perfil=` | “Seguidos por quem você segue” (em `UsuarioDao`) | `id_usuario` vem do cliente (consulta em nome de outro) |

Outros pontos do legado que tocam o domínio:
- **Feed** (`PostagemDao`, linha ~121): `JOIN relacao ON relacao.id_seguido = usuario.id WHERE relacao.id_seguidor = ? AND aceito = 1`.
  O feed **fica no legado** até Postagens migrar → precisa da tabela `relacao` no MySQL (§7).
- **Notificação de “seguir”**: criada pelo **frontend** (`notificationService.notificarSeguidor`) depois de seguir/aceitar, via
  `POST /api/notification` (rota forjável, SEC-02). Solicitações recebidas **não** geram notificação hoje.
- **Privacidade de postagens** (SEC-03 em `GET /api/postagem?id_user=`): é do domínio Postagens, mas a regra depende do grafo.
  Este serviço passa a oferecer a decisão (§6.4); a correção no legado continua fora deste escopo.
- Infra comum do legado: uma única `Connection` compartilhada (LOG-01), `ResultSet` atravessando camadas (LOG-02).

Esquema atual (`infra/mysql/schema.sql`): `relacao(id INT AI, id_seguidor, id_seguido, aceito, UNIQUE(id_seguidor,id_seguido),
FKs ON DELETE CASCADE para usuario)`. Pendentes e aceitos vivem na mesma tabela, distinguidos por `aceito`.

### 2.2 Como o frontend usa o domínio

| Arquivo | Uso |
| --- | --- |
| `services/relacaoService.ts` | `buscar`, `buscarSeguidos`, `buscarSeguidores`, `buscarPendentes`, `buscarEmComum`, `criar(user, profileUser)`, `remover(id)`, `aceitar(id)` |
| `hooks/useRelacao.ts` | Ao abrir um perfil baixa **as duas listas inteiras** + a relação própria; seguir/deixar de seguir; chama `notificarSeguidor` quando o seguir é aceito na hora |
| `hooks/useRelacoesPendentes.ts` | Lista pendentes; aceitar (e notificar) / recusar pelo `relacao.id` |
| `hooks/useRelacoesEmComum.ts`, `components/relacoes/*`, `SidebarRelacoes.tsx` | Em comum e cartões de solicitação |
| `components/perfil/ActionsProfile.tsx` | Botão “Seguir” / “Seguindo” / “Solicitado” a partir de `relacao` e `relacao.aceito` |
| `pages/Perfil/Perfil.tsx` | `podeVerConteudo = próprio || !privado || relacao?.aceito` (regra só na UI) |
| `types/api.ts` | `Relacao { id: number, seguidor: Usuario, seguido: Usuario, aceito: boolean }` |

### 2.3 O que os serviços novos já oferecem (e será reutilizado)

| Peça | Onde | Reuso no `social_graph_service` |
| --- | --- | --- |
| Resource server JWT RS256 via JWKS do auth, `sub` = UUIDv7 | `profile_service/security` | Igual (`aud` próprio) |
| Revogação imediata via `forward_auth` no Caddy | `Caddyfile` | Igual (novo `handle`) |
| `OriginCheckFilter`, `ApiException` + `GlobalExceptionHandler`, `Clock` injetado, `@ConfigurationProperties` | `profile_service` | Igual |
| Outbox transacional (`outbox_event` com `seq`), `OutboxRelay`, `AmqpEventPublisher`, inbox `processed_message`, DLQ `socialflux.events.dead` | `profile_service/events` | Igual |
| Projeção em tabela do MySQL legado por consumidor próprio (`LegacyProjectionHandler`/`Writer`) | `profile_service/legacy` | Mesmo padrão para `relacao` (§7) |
| Eventos `profile.created/updated/privacy-changed/deleted.v1` com `aggregateVersion` e `legacyId` | `profile_service` | **Consumidos** para manter a cópia local de usuários (§5.2) |
| Bootstrap de consumidores `GET /internal/profiles?cursor=` (escopo `profiles:read`) | `profile_service` | Ver D9: hoje **só o auth emite token de serviço** (`ServiceTokenIssuer`, `sub=svc:authentication-service`) |
| Testes com PostgreSQL embutido (zonky), JWKS local, JWT real | `profile_service` | Igual |

Lacunas encontradas nos serviços novos que afetam este plano:
- **Emissão de token de serviço** existe só para o próprio auth chamar o profile. Um terceiro serviço que precise chamar
  `/internal/*` (bootstrap) não tem como obter token (D9).
- `profile.privacy-changed.v1` **não diz o que fazer com pendentes** quando a conta vira pública — regra que passa a ser deste
  serviço (D5).
- Código de outbox/inbox/AMQP será **duplicado** pela terceira vez (auth, profile, grafo). O plano do perfil previa um *starter*
  pequeno; ver D13.

---

## 3. Escopo do `social_graph_service`

**Dentro:** seguir/deixar de seguir; solicitações para contas privadas (criar, cancelar, aceitar, recusar); remover um seguidor
(opcional, D7); listas paginadas de seguidores/seguidos; contagens; seguidores em comum; estado da relação entre o usuário logado
e outro; regra de visibilidade “pode ver conteúdo” (API interna para Postagens/Notificações futuros); reação a
`profile.*` (cópia local, privacidade, exclusão); eventos `follow.*`; projeção temporária em `relacao` do MySQL.

**Fora (agora):** bloqueio e silenciar (modelo preparado, fase futura — §12); sugestões “pessoas que talvez você conheça”
(§4.5); notificações (continuam no legado; o futuro serviço de notificações consumirá `follow.*`); feed e privacidade de
postagens (domínio Postagens).

---

## 4. Análise: SQL ou NoSQL para o grafo social

### 4.1 Padrões de acesso (o que o banco precisa responder bem)

| # | Consulta / operação | Frequência | Forma |
| --- | --- | --- | --- |
| A1 | “A segue B?” / estado da relação (perfil, regra de visibilidade de **cada** post/perfil) | **Muito alta** | busca pontual por par |
| A2 | Seguir, deixar de seguir, aceitar, recusar, cancelar | Média | escrita com **transição de estado** + contador + evento |
| A3 | Seguidores / seguidos de X, paginado, mais recentes primeiro | Alta | varredura por faixa de índice (1 salto) |
| A4 | Contagens de seguidores / seguidos / pendentes | Alta | leitura de contador |
| A5 | Solicitações pendentes recebidas por X | Média | varredura por faixa |
| A6 | Seguidores em comum (quem eu sigo e segue X) | Média | interseção de 2 saltos |
| A7 | Conta vira pública → aceitar pendentes; conta excluída → apagar arestas dos dois lados | Rara | escrita em lote |
| A8 | Sugestões (amigos de amigos), caminhos longos | **Não existe hoje** | 2–3+ saltos, agregação |

Requisitos não funcionais do projeto que pesam na escolha: **evento gravado no outbox na mesma transação da mudança**
(CLAUDE.md), unicidade forte do par (não pode haver duas relações A→B), invariante “um par não é ao mesmo tempo seguidor e
pendente”, migrações versionadas (Flyway), testes sem Docker, banco **sempre via Docker** e equipe pequena.

### 4.2 Candidatos

| Critério | **PostgreSQL** (lista de adjacência) | Neo4j (grafo nativo) | Cassandra/ScyllaDB (wide-column) | MongoDB (documento) | Redis (chave-valor/sets) |
| --- | --- | --- | --- | --- | --- |
| A1 segue? | PK `(follower, followee)` → 1 busca em índice | `MATCH (a)-[:FOLLOWS]->(b)` rápido | partição por par; ótimo | índice composto; ótimo | `SISMEMBER`; ótimo |
| A3 listas paginadas | índice `(followee, created_at)` — paginação por cursor | bom; paginação por `SKIP` é cara, precisa ordenar por propriedade | ótimo, **mas** exige 2 tabelas desnormalizadas (por seguidor e por seguido) | bom com 2 índices; documento com array de seguidores **não escala** (limite de 16 MB) | ZSET por usuário; bom, mas tudo em memória |
| A2 transição de estado + contador + outbox **atômicos** | **ACID nativo**; outbox na mesma transação | ACID, mas **sem ecossistema de outbox** e sem Flyway/Spring Data JPA (Spring Data Neo4j é outro modelo) | **sem transação** entre as 2 tabelas e o outbox; unicidade só com LWT (Paxos, lento); contadores com tipo `counter` não idempotente | transações multi-documento existem (replica set), outbox possível, menos maduro no time | sem durabilidade/consulta para ser fonte da verdade |
| A6 em comum (2 saltos) | 1 JOIN usando a PK — barato para listas de centenas/milhares | **nativo e elegante** | inviável sem pré-computar | `$lookup`, caro | `SINTER` rápido (se tudo couber em memória) |
| A8 3+ saltos / recomendação | CTE recursiva; cara em grafos grandes → pré-computar | **ponto forte real** | inviável | inviável | inviável |
| Operação no projeto | **já existe** (Postgres 17 no compose, um banco por serviço) | novo container, licença (Community GPLv3 / Enterprise comercial), backup próprio, nova linguagem (Cypher) | cluster de 3+ nós para fazer sentido; modelagem por consulta | novo container | já seria novo; útil como cache |
| Testes sem Docker | zonky embutido (igual ao profile) | Neo4j harness (JVM) ou Testcontainers | Testcontainers obrigatório | Testcontainers/flapdoodle | embedded-redis / Testcontainers |
| Escala típica | centenas de milhões de arestas numa instância bem indexada; depois particionamento por hash/sharding | bilhões com cluster Enterprise | bilhões, escrita massiva | bom | limitado à RAM |

### 4.3 Referências públicas do mercado
- **Facebook TAO** — o grafo social (objetos e associações) é persistido em **MySQL fragmentado** com uma camada de cache de grafo
  na frente.
- **Twitter FlockDB** — banco de grafo de seguidores construído **sobre MySQL** fragmentado, justamente porque as consultas
  são de 1 salto (listas, interseções, contagens), não de travessia profunda.
- **Instagram** — relações armazenadas em **PostgreSQL fragmentado**.

Ou seja: redes sociais grandes resolveram “seguir/seguidores” com **lista de adjacência em banco relacional + cache**, e usam
motores de grafo/ML à parte para recomendação. O ponto forte de um banco de grafo nativo (travessias profundas) não é o padrão
de acesso dominante de um grafo de seguidores.

### 4.4 Estimativa de volume (ordem de grandeza)
Cenário otimista para o projeto: 1 milhão de usuários × 200 relações em média = **200 milhões de arestas**. Linha de ~90 bytes
(2 UUIDs + UUID da relação + data + cabeçalho) ≈ 18 GB de tabela + 2 índices secundários ≈ 15–20 GB. Cabe numa instância
PostgreSQL comum; A1 continua O(log n) por índice. Antes disso, o gargalo previsível é o **contador de usuários celebridade**
(linha quente) e o fan-out do feed — o segundo é do domínio Postagens. Mitigações em §12.

### 4.5 Veredito
**PostgreSQL** (decisão D1), porque:
1. cobre com folga A1–A7, que são 100% do uso atual e previsto do produto;
2. é o **único candidato que atende a regra do outbox transacional sem trabalho extra** — a transição de estado, o contador e o
   evento ficam numa transação;
3. unicidade, `CHECK (follower <> followee)`, FKs locais e invariantes ficam no banco, não só no código;
4. **zero infraestrutura nova** (mesma instância, banco `social_graph`), mesmo stack de testes, Flyway e Spring Data JPA;
5. é reversível: os eventos `follow.*` permitem montar depois um *read model* em Neo4j (recomendações) ou Redis (cache de
   contagens/“segue?”) **sem migrar a fonte da verdade**.

Quando reconsiderar NoSQL: (a) recomendação por travessias de 3+ saltos virar funcionalidade central → Neo4j **como projeção**;
(b) A1/A4 virarem gargalo medido → Redis como cache; (c) arestas na casa de bilhões com escrita massiva → particionar
(`PARTITION BY HASH`) antes de trocar de banco.

---

## 5. Modelo de dados (PostgreSQL, Flyway)

### 5.1 Decisão de modelagem: arestas aceitas e solicitações em tabelas separadas (D2)
O legado usa uma tabela com `aceito`. Proposta: **`follow`** (só arestas aceitas = o grafo) e **`follow_request`** (pendentes).
Vantagens: toda consulta do grafo (listas, contagens, em comum, visibilidade, projeção) ignora pendentes sem filtro; índices
menores; a solicitação tem ciclo de vida próprio (é apagada ao aceitar/recusar/cancelar); recusas **não deixam rastro**.
Alternativa: tabela única com `status` (mais parecida com o legado; um índice parcial por status).

### 5.2 Esquema `V1__social_graph.sql`
```sql
-- Cópia local, somente leitura, do perfil PÚBLICO (alimentada por profile.*). Nenhum dado pessoal.
create table user_snapshot (
    user_id          uuid primary key,                 -- = sub do JWT = id do perfil
    legacy_id        integer unique,                   -- TEMPORÁRIO: id inteiro do legado (rotas e projeção MySQL)
    username         varchar(30)  not null,
    display_name     varchar(120) not null,
    avatar_key       varchar(80),
    is_private       boolean      not null,
    status           varchar(10)  not null check (status in ('ACTIVE','DELETED')),
    profile_version  bigint       not null,            -- aggregateVersion aplicado (ignora eventos antigos)
    updated_at       timestamp with time zone not null
);

-- Arestas aceitas: follower SEGUE followee.
create table follow (
    follower_id  uuid not null references user_snapshot (user_id),
    followee_id  uuid not null references user_snapshot (user_id),
    relation_id  uuid not null unique,                 -- UUIDv7; novo a cada vínculo (vai nos eventos)
    origin       varchar(16) not null check (origin in ('DIRECT','REQUEST_ACCEPTED','AUTO_ON_PUBLIC')),
    created_at   timestamp with time zone not null,    -- quando passou a seguir
    primary key (follower_id, followee_id),            -- A1 e unicidade do par
    constraint follow_not_self check (follower_id <> followee_id)
);
create index idx_follow_followers on follow (followee_id, created_at desc, follower_id);   -- A3 seguidores
create index idx_follow_following on follow (follower_id, created_at desc, followee_id);   -- A3 seguidos

-- Solicitações pendentes para contas privadas.
create table follow_request (
    requester_id  uuid not null references user_snapshot (user_id),
    target_id     uuid not null references user_snapshot (user_id),
    request_id    uuid not null unique,
    created_at    timestamp with time zone not null,
    primary key (requester_id, target_id),
    constraint follow_request_not_self check (requester_id <> target_id)
);
create index idx_follow_request_received on follow_request (target_id, created_at desc, requester_id);  -- A5
create index idx_follow_request_sent     on follow_request (requester_id, created_at desc);

-- Contadores desnormalizados (A4), atualizados na MESMA transação da mudança.
create table follow_stats (
    user_id          uuid primary key references user_snapshot (user_id),
    followers_count  bigint not null default 0 check (followers_count >= 0),
    following_count  bigint not null default 0 check (following_count >= 0),
    pending_count    bigint not null default 0 check (pending_count   >= 0)
);

-- Outbox e inbox: mesmas tabelas do profile_service.
create table outbox_event (
    seq bigint generated always as identity primary key, id uuid not null unique, type varchar(80) not null,
    aggregate_id uuid not null, aggregate_version bigint not null, payload text not null,
    created_at timestamp with time zone not null, published_at timestamp with time zone);
create index idx_outbox_unpublished on outbox_event (seq) where published_at is null;
create table processed_message (id uuid primary key, processed_at timestamp with time zone not null);
```

Consultas centrais:
```sql
-- A6: em comum = usuários que EU sigo e que seguem :perfil (usa só PKs/índices)
select f1.followee_id
from follow f1
join follow f2 on f2.follower_id = f1.followee_id and f2.followee_id = :perfil
where f1.follower_id = :eu
order by f1.created_at desc
limit :limite;

-- A3: seguidores de :usuario com cursor (keyset), sem OFFSET
select follower_id, created_at from follow
where followee_id = :usuario and (created_at, follower_id) < (:cursorData, :cursorId)
order by created_at desc, follower_id desc
limit :limite;
```

### 5.3 Concorrência e invariantes
- Toda operação sobre um par `(A,B)` começa com `pg_advisory_xact_lock(hash(A,B))` — serializa cliques duplos, “seguir” ×
  “aceitar” simultâneos etc., sem travar linhas quentes de celebridades.
- Invariante “o par não está em `follow` e `follow_request` ao mesmo tempo” garantida pelo serviço sob o lock (e coberta por
  teste de concorrência). Inserções com `on conflict do nothing` → operações **idempotentes**.
- Contadores: `update follow_stats set followers_count = followers_count + 1 …` na mesma transação. A linha de uma celebridade
  vira ponto quente só sob milhares de seguidas por segundo (§12).
- Reconciliador agendado (diário) recalcula `follow_stats` por `count(*)` e corrige divergências (rede de segurança).

---

## 6. Regras de negócio

### 6.1 Máquina de estados do par (eu → alvo)
| De | Ação (quem) | Para | Evento |
| --- | --- | --- | --- |
| NENHUM | seguir, alvo público (eu) | SEGUINDO | `follow.created.v1` (`DIRECT`) |
| NENHUM | seguir, alvo privado (eu) | SOLICITADO | `follow.requested.v1` |
| SOLICITADO | cancelar (eu) | NENHUM | `follow.request-cancelled.v1` |
| SOLICITADO | recusar (alvo) | NENHUM | `follow.request-rejected.v1` |
| SOLICITADO | aceitar (alvo) | SEGUINDO | `follow.created.v1` (`REQUEST_ACCEPTED`) |
| SOLICITADO | alvo virou público (sistema, D5) | SEGUINDO | `follow.created.v1` (`AUTO_ON_PUBLIC`) |
| SEGUINDO | deixar de seguir (eu) | NENHUM | `follow.removed.v1` (`UNFOLLOW`) |
| SEGUINDO | remover seguidor (alvo, D7) | NENHUM | `follow.removed.v1` (`REMOVED_BY_FOLLOWEE`) |
| qualquer | conta de um dos dois excluída (`profile.deleted.v1`) | NENHUM | nenhum (cada domínio reage a `profile.deleted.v1`) |

### 6.2 Regras
| Regra | Detalhe |
| --- | --- |
| Identidade | Sempre do `sub` do JWT. O id na rota é o **alvo** (UUID ou inteiro legado, resolvido por `user_snapshot`); nunca há seguidor no corpo |
| Alvo válido | Existe em `user_snapshot` e está `ACTIVE`; senão **404 uniforme** `usuario_nao_encontrado` |
| A si mesmo | `422 seguir_a_si_mesmo` (+ `CHECK` no banco) |
| Público × privado | Decidido pelo `is_private` **da cópia local** (nunca do cliente). Janela de consistência eventual: ver D4 |
| Idempotência | Seguir quando já segue/solicitou → **200 com o estado atual** (o legado devolvia 409). Deixar de seguir/cancelar sem relação → 204 |
| Aceitar/recusar | Só o **alvo** da solicitação; solicitação inexistente → 404 `solicitacao_nao_encontrada` |
| Público → privado | Seguidores atuais **permanecem** (comportamento usual de redes sociais) |
| Privado → público | Pendentes são **aceitas automaticamente** em lote, com evento por aresta (D5) |
| Limites antiabuso | Rate limit de seguir/deixar de seguir por usuário (ex.: 60/h) e teto de solicitações pendentes enviadas (ex.: 1.000) e de seguidos (ex.: 7.500) — valores em D8 |
| Exclusão de conta | `profile.deleted.v1` → apaga arestas e solicitações **dos dois lados**, ajusta contadores dos afetados, marca o snapshot `DELETED`. Sem evento por aresta: cada domínio já reage a `profile.deleted.v1` |

### 6.3 Quem vê o quê
| Dado sobre o usuário X | Quem pode ver |
| --- | --- |
| Contagens (seguidores/seguidos) | Qualquer logado (inclusive de conta privada) |
| Lista de seguidores/seguidos | X; qualquer logado se X é público; **seguidores aceitos de X** se X é privado. Demais: **403 `conta_privada`** |
| Estado da minha relação com X | Eu (é dado meu) |
| Solicitações recebidas/enviadas | Só o próprio usuário |
| Em comum (quem eu sigo e segue X) | Qualquer logado (D6) |

### 6.4 Regra de visibilidade para outros domínios
`podeVer(visualizador, dono) = visualizador == dono || !dono.is_private || existe follow(visualizador → dono)`.
Exposta em `/internal/**` para o futuro serviço de Postagens (e para o legado não — ele continua usando o JOIN no MySQL).

---

## 7. Transição com o legado

1. **Rotas**: o Caddy passa a mandar as rotas de relação para o novo serviço **antes** do `handle /api/*` genérico (mesma técnica
   do `@profile`). O legado perde `RelacaoController`, `RelacaoService`, `RelacaoDao`, `UsuarioDao`/`UsuarioService`
   (só tinham o “em comum”), `Relacao.java` e as 5 rotas em `App.java` — remoção permitida pela regra do legado congelado.
2. **Feed do legado**: continua com `JOIN relacao … aceito = 1`. O `social_graph_service` mantém `relacao` como **projeção**
   (consumidor próprio, `GRAPH_LEGACY_ENABLED=true`, igual ao `profile_service`):
   - Projeta **só arestas aceitas** (`aceito = 1`); pendentes não interessam ao legado depois que as rotas saírem dele.
   - **Projeção por estado, não por evento**: ao receber qualquer `follow.*` do par (A,B), o projetor **lê o estado atual do par
     no próprio PostgreSQL** e faz `INSERT … ON DUPLICATE KEY UPDATE` ou `DELETE` no MySQL. Isso é idempotente e imune a eventos
     fora de ordem (ex.: “deixou de seguir R1” chegando depois de “seguiu de novo R2”) **sem mudar o `schema.sql`**.
   - Ids: usa `user_snapshot.legacy_id` dos dois lados. Se o `usuario` ainda não foi projetado pelo profile (corrida no
     cadastro), a FK do MySQL falha → nova tentativa com atraso; persistindo, DLQ.
   - Exclusão de conta: o `ON DELETE CASCADE` da FK de `relacao` para `usuario` já limpa o MySQL quando o profile remove o usuário.
3. **Notificação de seguir**: continua no legado, criada pelo frontend como hoje (o legado não consome RabbitMQ e está congelado).
   Os eventos `follow.*` já nascem prontos para o futuro serviço de notificações, que fará o servidor gerar a notificação e
   permitirá remover `POST /api/notification` (D7 da análise do legado).
4. **Fim da transição**: quando Postagens migrar, sai o projetor, `relacao` e `legacy_id`.

---

## 8. API

### 8.1 Contrato público (proposta — D3)
Base `/api/relacoes`. `{usuario}` = UUID **ou** id inteiro legado (como no profile). Item de usuário nas listas = mesma visão
pública do profile (`id`, `accountId`, `username`, `nome`, `foto_perfil`, `foto_url`, `privado`), montada a partir do
`user_snapshot` (sem N+1 de rede).

| Rota | Quem | Resposta |
| --- | --- | --- |
| `GET /api/relacoes/{usuario}/estado` | logado | `{ estado: "NENHUM"\|"SOLICITADO"\|"SEGUINDO", segueVoce: boolean, solicitouVoce: boolean }` |
| `GET /api/relacoes/{usuario}/contagens` | logado | `{ seguidores, seguidos }` |
| `PUT /api/relacoes/seguindo/{usuario}` | logado | Seguir. `201`/`200 { estado: "SEGUINDO"\|"SOLICITADO" }` |
| `DELETE /api/relacoes/seguindo/{usuario}` | logado | Deixar de seguir **ou** cancelar solicitação. `204` |
| `DELETE /api/relacoes/seguidores/{usuario}` | logado | Remover um seguidor meu (D7). `204` |
| `GET /api/relacoes/{usuario}/seguidores?cursor=&limite=` | ver §6.3 | `{ itens: Usuario[], proximoCursor: string \| null }` (limite padrão 20, máx. 100) |
| `GET /api/relacoes/{usuario}/seguidos?cursor=&limite=` | ver §6.3 | idem |
| `GET /api/relacoes/{usuario}/em-comum?limite=` | logado | `{ itens: Usuario[], total }` |
| `GET /api/relacoes/solicitacoes?cursor=&limite=` | dono | Solicitações **recebidas**: `{ itens: [{ usuario, solicitadoEm }], proximoCursor }` |
| `GET /api/relacoes/solicitacoes/enviadas?cursor=` | dono | Solicitações enviadas |
| `POST /api/relacoes/solicitacoes/{usuario}/aceitar` | dono (alvo) | `204` |
| `DELETE /api/relacoes/solicitacoes/{usuario}` | dono (alvo) | Recusar. `204` |

Erros (`{ mensagem, sucesso: false, codigo }`): `usuario_nao_encontrado` 404 · `seguir_a_si_mesmo` 422 · `conta_privada` 403 ·
`solicitacao_nao_encontrada` 404 · `limite_seguidos` 422 · `muitas_requisicoes` 429 · `parametro_invalido` 400 ·
`origem_invalida` 403. Mensagens em pt-BR.

Por que mudar o contrato (em vez de manter `/api/relacao` como o profile manteve `/api/usuarios`): o contrato legado é a
**causa** das falhas (identidade no corpo, objetos inteiros do usuário enviados pelo cliente, ações por `id` de relação, 200 vazio,
listas sem paginação). Como não há dados nem clientes externos, o custo de adaptar o frontend é baixo e concentrado (§9).
Alternativa (D3): manter as rotas antigas com regras corrigidas por uma fase.

### 8.2 API interna (`/internal/**`, token de serviço, bloqueada no Caddy)
| Rota | Escopo | Uso |
| --- | --- | --- |
| `GET /internal/relations/visibility?viewer=&owner=` | `relations:read` | `{ podeVer }` (Postagens) |
| `POST /internal/relations/visibility/batch` | `relations:read` | até 100 donos por chamada (montar feed/listas sem N+1) |
| `GET /internal/relations/{userId}/following?cursor=` | `relations:read` | bootstrap do futuro feed/notificações |

Nenhum serviço chama essas rotas ainda; podem ficar para a fase do domínio Postagens (D10).

---

## 9. Eventos

Exchange `socialflux.events`, routing key = tipo, outbox na mesma transação. `aggregateId` = `relation_id`/`request_id`
(novo a cada vínculo, então `aggregateVersion` nunca “volta”). **Sem dados pessoais** — só UUIDs e datas.

| Produz | Quando | `data` | Consumidores previstos |
| --- | --- | --- | --- |
| `follow.requested.v1` | solicitação criada | `requestId, requesterId, targetId, requestedAt` | notificações (futuro) |
| `follow.request-cancelled.v1` | solicitante cancelou | `requestId, requesterId, targetId` | notificações (apagar aviso pendente) |
| `follow.request-rejected.v1` | alvo recusou | `requestId, requesterId, targetId` | (nenhum hoje; não notifica o solicitante) |
| `follow.created.v1` | passou a seguir | `relationId, followerId, followeeId, origin (DIRECT\|REQUEST_ACCEPTED\|AUTO_ON_PUBLIC), createdAt` | projetor MySQL; notificações; feed |
| `follow.removed.v1` | deixou de seguir / foi removido | `relationId, followerId, followeeId, reason (UNFOLLOW\|REMOVED_BY_FOLLOWEE)` | projetor MySQL; feed |

| Consome | Ação |
| --- | --- |
| `profile.created.v1` / `profile.updated.v1` | *upsert* em `user_snapshot` (+ cria `follow_stats`), ignorando `aggregateVersion` antigo |
| `profile.privacy-changed.v1` | atualiza `is_private`; se virou público, aceita pendentes em lote (D5) |
| `profile.deleted.v1` | apaga arestas/solicitações dos dois lados, ajusta contadores, snapshot `DELETED` |

Filas: `social-graph.profile-events` (`profile.#`) e `social-graph.legacy-projection` (`follow.#`), ambas com DLQ.

---

## 10. Mudanças por pasta

| Pasta / arquivo | Mudança | Exige seu OK (CLAUDE.md) |
| --- | --- | --- |
| `social_graph_service/` (novo) | Serviço completo: `config/ domain/ service/ security/ web/ events/ legacy/`, `V1__social_graph.sql`, `Dockerfile`, `README.md`, `docs/ARCHITECTURE.md`, testes | dependências (as mesmas do profile) |
| `docker-compose.yml` | serviço `social_graph_service` (perfil `apps`, porta 8083), criação do banco `social_graph` (estender o `profile_db_init` ou um `graph_db_init`) e variáveis | **sim** |
| `infra/caddy/Caddyfile` | `@graph path /api/relacoes /api/relacoes/*` → `forward_auth` + `reverse_proxy {$GRAPH_UPSTREAM}` antes de `/api/*`; atualizar `gateway_host` | **sim** |
| `infra/mysql/schema.sql` | **Nenhuma** (projeção por estado usa a `relacao` atual) | — |
| `scripts/dev/` | `run-graph.ps1` / `run-graph.sh` | — |
| `.env.example`, `README.md`, `docs/ARCHITECTURE.md` da raiz, `CLAUDE.md` (tabela de estrutura) | variáveis `GRAPH_*`, porta, diagrama | — |
| `BackEnd/` | remover código/rotas de relação (§7.1); `docs/ARCHITECTURE.md` do legado | — |
| `authentication_service/` | só se D9 = emissão de token para outros serviços | **sim** (lógica de token) |
| `FrontEnd/` | `services/relacaoService.ts` (novo contrato), `types/api.ts` (`EstadoRelacao`, `Pagina<T>`, `ContagensRelacao`), `useRelacao` (estado + contagens; listas carregadas ao abrir o modal, com “carregar mais”), `useRelacoesPendentes` (por usuário, não por `relacao.id`), `useRelacoesEmComum`, `ActionsProfile`/`Perfil.tsx` (usar `estado`), `ModalListUsers` (paginação), `docs/COMPONENTS.md` | contrato de API: **sim** (D3) |

Configuração do serviço (variáveis): `GRAPH_DB_URL/NAME/USER/PASSWORD` (reutilizam `IDENTITY_DB_*` se ausentes, como o
profile), `AUTH_JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE`, `GRAPH_SERVICE_AUDIENCE`, `RABBITMQ_*`, `EVENTS_EXCHANGE`,
`GRAPH_EVENTS_ENABLED`, `GRAPH_LEGACY_ENABLED`, `LEGACY_DB_URL/USER/PASSWORD`, `GRAPH_ALLOWED_ORIGINS`,
`GRAPH_FOLLOW_RATE_PER_HOUR`, `GRAPH_MAX_FOLLOWING`, `GRAPH_MAX_PENDING_SENT`.

---

## 11. Fases

Cada fase termina com `mvn test` verde, docs atualizados e um commit por tarefa num branch `feat/grafo-social-*`.

| Fase | Entrega | Pronto quando | Dias |
| --- | --- | --- | --- |
| 0 | Decisões D1–D14 fechadas | registradas neste arquivo | ✔ |
| 0.5 | Módulo compartilhado de eventos (§11.1); auth e profile passam a usá-lo | 31 testes do auth e 41 do profile verdes, sem mudar comportamento | 2–3 |
| 1 | Esqueleto: Maven/Java 21/Spring Boot 4 (mesma versão do profile), Flyway `V1`, resource server via JWKS, `OriginCheckFilter`, formato de erro, `Clock`, Dockerfile, compose, script dev, Caddyfile | sobe recusando config incompleta; JWT do auth aceito, adulterado/`aud` errado recusado; `/internal/*` dá 404 no gateway | 2 |
| 2 | Cópia local de usuários: consumidor `profile.*` + inbox, `user_snapshot`, `follow_stats`, exclusão em cascata | evento duplicado/antigo é inócuo; `profile.deleted` limpa os dois lados | 2 |
| 3 | Núcleo do grafo: máquina de estados, locks, contadores, limites/rate limit, todas as rotas públicas, regras de visibilidade, paginação por cursor | testes de IDOR, conta privada, auto-seguir, idempotência, concorrência (seguir × aceitar em paralelo), paginação estável | 4–5 |
| 4 | Outbox + eventos `follow.*`; privado→público aceita pendentes; projetor MySQL por estado; reconciliador de contadores | seguir reflete na `relacao` do MySQL e no feed do legado; eventos fora de ordem não corrompem a projeção; broker fora do ar não perde evento | 3 |
| 5 | Frontend no novo contrato (§10) + rota no gateway | fluxo no navegador: seguir público, solicitar privado, cancelar, aceitar, recusar, deixar de seguir, remover seguidor, listas paginadas, em comum, mobile | 2–3 |
| 6 | Limpeza do legado (remoção de relação) e docs (READMEs, ARCHITECTURE da raiz e do legado, CLAUDE.md, `.env.example`) | legado compila; E2E completo verde | 1–2 |

**Total: ≈ 17–22 dias úteis** (≈ 4 semanas), incluindo a Fase 0.5. O que mais pesa é a fase 3 (regras e concorrência) e a 4 (eventos e projeção).

### 11.1 Fase 0.5 — módulo compartilhado de eventos (D13)

**Situação hoje** (comparação feita em 2026-11-12): `Outbox`, `OutboxRelay`, `EventPublisher` e `AmqpEventPublisher` estão
copiados no auth e no profile, quase idênticos — diferem só no prefixo das propriedades (`auth.events.*` × `profile.events.*`),
em comentários e em uma função: o auth publica com **`mandatory`** (evento sem fila ligada fica pendente, porque o profile é
consumidor obrigatório), o profile não. `Inbox` e a topologia de consumo (`Amqp`: exchange, DLX `socialflux.events.dlx`, fila
`socialflux.events.dead`) só existem no profile. As tabelas `outbox_event`/`processed_message` são iguais nos dois.

**Proposta:** biblioteca Spring Boot com auto-configuração, `libs/socialflux-events/` (`com.socialflux:socialflux-events`,
pacote `com.socialflux.events`):

| Peça | Conteúdo |
| --- | --- |
| `Outbox` | `add(type, aggregateId, version, data)` (exige transação ativa), `lockPending`, `markPublished`, `deletePublishedBefore`, `countPending` |
| `OutboxRelay` | publicação agendada em ordem + limpeza de publicados com mais de 7 dias |
| `EventPublisher` / `AmqpEventPublisher` | mensagem persistente, *publisher confirms*, `mandatory` configurável por serviço |
| `Inbox` | `once(messageId, action)` na mesma transação do efeito |
| `EventTopology` | declara o exchange `topic`, a DLX e a fila de mortos; *helper* para o serviço declarar a **sua** fila com DLX e o padrão de ligação |
| `EventEnvelope` | leitura do envelope `{id, type, occurredAt, aggregateId, aggregateVersion, data}` para consumidores |
| `SocialFluxEventsProperties` | `socialflux.events.enabled`, `exchange`, `relay-interval`, `mandatory`, `retention` |

O que **não** vai para a biblioteca: migrações Flyway (cada serviço mantém as tabelas nas suas próprias migrações, que não
mudam), tipos de evento, *handlers* e filas de cada serviço. As variáveis de ambiente atuais (`PROFILE_EVENTS_ENABLED`,
`EVENTS_EXCHANGE`…) continuam as mesmas: cada `application.yml` só aponta as novas chaves `socialflux.events.*` para elas.

**Build (proposta — precisa do seu OK por alterar o `docker-compose.yml`):**
- `pom.xml` agregador na raiz (só lista módulos: `libs/socialflux-events`, `authentication_service`, `profile_service`,
  `social_graph_service`); cada serviço continua com o `spring-boot-starter-parent` 4.1.2 e passa a depender de
  `socialflux-events`.
- Comandos: na raiz, `mvn -pl profile_service -am test` (compila a biblioteca junto); dentro da pasta do serviço, `mvn test`
  funciona depois de `mvn -q install -pl libs/socialflux-events` uma vez. `scripts/dev/run-*.{ps1,sh}` e o CLAUDE.md são atualizados.
- Docker: o `build` de auth/profile/grafo passa a usar a **raiz como contexto** (`context: .`, `dockerfile: <serviço>/Dockerfile`)
  para o Dockerfile copiar a biblioteca; `.dockerignore` na raiz para não enviar `node_modules`, `target` etc.
- Alternativas descartadas: publicar a biblioteca num repositório Maven (GitHub Packages) — infraestrutura demais para um
  monorepo; copiar o código-fonte por *build-helper* — esconde a dependência.

**Entrega:** branch e PR próprios (`refactor/modulo-eventos`), antes do serviço novo; auth e profile migrados sem mudança de
comportamento (mesmos eventos, mesmas filas, mesmos testes verdes) e testes próprios da biblioteca (PostgreSQL embutido).

---

## 12. Testes, riscos e evolução

### 12.1 Testes
| Camada | Cobertura |
| --- | --- |
| Unidade | máquina de estados (todas as transições e as inválidas), regra `podeVer`, codificação do cursor, limites |
| Integração (Postgres embutido) | rotas com JWT real; contadores após cada operação; `CHECK`/PK; consulta de em comum; paginação com empates de data |
| Segurança | IDOR em **cada** rota mutável (aceitar solicitação alheia, remover seguidor alheio, ler pendentes alheias); lista de conta privada por não seguidor → 403; id do usuário no corpo ignorado; token de serviço em rota pública e vice-versa |
| Concorrência | N threads seguindo/deixando de seguir o mesmo par; seguir × aceitar × virar público ao mesmo tempo → invariantes e contadores corretos |
| Eventos | inbox (duplicado), `aggregateVersion` antigo, ordem trocada na projeção MySQL, broker indisponível |
| E2E (infra real) | cadastro de 2 usuários → seguir/solicitar/aceitar → feed do legado mostra posts → excluir conta limpa Postgres e MySQL |

### 12.2 Riscos
- **Privacidade eventualmente consistente**: entre a mudança para privado e o evento chegar (ms–s), alguém pode seguir direto. D4.
- **Dupla escrita lógica Postgres → MySQL** (como no perfil): divergência temporária; mitigada por projeção por estado e reprocessamento.
- **Corrida de cadastro**: seguir um usuário recém-criado antes do `profile.created.v1` chegar → 404 por alguns ms (aceitável) ou
  FK do MySQL falhando na projeção (retry).
- **Contador quente** de contas muito seguidas: só sob carga muito alta; evolução: contadores em lote/sharded counter ou Redis.
- **Mudança de contrato no frontend** (D3): concentrada em 1 service, 3 hooks e 3–4 componentes.
- **Extração do módulo de eventos** (D13) mexe em auth e profile, que já funcionam: mitigado por não mudar comportamento e manter as duas suítes de testes verdes antes de seguir.

### 12.3 Evolução prevista (fora deste plano)
Bloqueio (`block(blocker_id, blocked_id)`: desfaz arestas nos dois sentidos e impede novas — entra na regra `podeVer`);
silenciar; sugestões pré-computadas a partir dos eventos (Neo4j ou job SQL noturno); cache Redis para A1/A4 se medido;
particionamento por hash de `follow` em volume muito alto; troca do `id` inteiro por UUID no frontend quando o último domínio migrar.

---

## 13. Decisões

**Decididas em 2026-11-12:**

| # | Decisão |
| --- | --- |
| D1 | PostgreSQL, banco `social_graph` na mesma instância |
| D2 | Tabelas separadas: `follow` (aceitas) e `follow_request` (pendentes) |
| D3 | Novo contrato `/api/relacoes/*` com paginação; o frontend é adaptado |
| D4 | `privado` lido da cópia local (`user_snapshot`) alimentada por eventos |
| D5 | Conta que vira pública aceita automaticamente todas as solicitações pendentes |
| D6 | “Em comum” visível a qualquer usuário logado |
| D7 | “Remover seguidor” entra nesta entrega |
| D8 | Limites antiabuso ativos com os valores recomendados (60 seguir/h, 1.000 pendentes enviadas, 7.500 seguidos), configuráveis por variável de ambiente — carga esperada é baixa |
| D9 | Sem token de serviço para o grafo por enquanto (sem bootstrap; cópia nasce dos eventos) |
| D10 | API interna de visibilidade **fora desta entrega**; volta a ser discutida no plano do domínio Postagens |
| D11 | `social_graph_service/`, pacote `com.socialflux.graph`, porta 8083, eventos `follow.*` |
| D12 | Seguir é idempotente (200 com o estado atual) |
| D13 | **Extrair agora** o código de outbox/inbox/AMQP para um módulo compartilhado (outros serviços publicarão e consumirão eventos) — §11.1 |
| D14 | Notificação de seguir continua criada pelo frontend no legado (mudança prevista em breve) |

**Opções avaliadas (registro):**

| # | Decisão | Recomendado (em **negrito**) · alternativas |
| --- | --- | --- |
| D1 | Banco do grafo | **PostgreSQL, banco `social_graph` na instância existente** · Neo4j · Cassandra/ScyllaDB · MongoDB (§4) |
| D2 | Modelagem | **`follow` (aceitas) + `follow_request` (pendentes) separadas** · tabela única com `status` como no legado |
| D3 | Contrato HTTP | **Novo `/api/relacoes/*` (por usuário, paginado) e adaptar o frontend** · manter `/api/relacao` com regras corrigidas por uma fase |
| D4 | Fonte do `privado` ao seguir | **Cópia local por evento** (coerente com D6 do perfil) · consulta síncrona ao profile só nesse momento (exige token de serviço, D9) |
| D5 | Conta vira pública | **Aceitar todas as pendentes automaticamente** · manter pendentes até o dono decidir · descartar pendentes |
| D6 | “Em comum” de conta privada | **Visível a qualquer logado** (como hoje na UI) · só para quem pode ver os seguidores de X |
| D7 | Remover seguidor (o seguido tira alguém da lista) | **Incluir agora** (essencial para conta privada) · deixar para depois |
| D8 | Limites antiabuso | **60 ações de seguir/h, 1.000 solicitações pendentes enviadas, 7.500 seguidos** · outros valores · sem teto |
| D9 | Token de serviço para o grafo chamar `/internal/profiles` (bootstrap) | **Não agora**: sem dados reais, a cópia local nasce dos eventos; documentar “recriar volumes” no dev · adicionar emissão *client credentials* no auth já |
| D10 | API interna de visibilidade (`/internal/relations/*`) | **Deixar para a fase do domínio Postagens** · entregar já |
| D11 | Nome / pasta / porta | **`social_graph_service/`, pacote `com.socialflux.graph`, porta 8083, eventos `follow.*`** · `relationship_service` / `relations.*` |
| D12 | Seguir quando já segue | **Idempotente: 200 com o estado atual** · 409 como o legado |
| D13 | Código de outbox/inbox/AMQP repetido | **Copiar agora (3º serviço) e extrair um *starter* pequeno quando houver o 4º** · extrair já (exige mexer em auth e profile) |
| D14 | Notificação de seguir durante a transição | **Continua criada pelo frontend no legado** (como hoje) · antecipar o serviço de notificações |

## 14. O que não foi verificado
Nada foi executado. As estimativas de volume (§4.4) são ordem de grandeza, não medição. As referências de mercado (§4.3) vêm de
publicações técnicas públicas dessas empresas e descrevem arquiteturas que podem ter mudado. Não confirmei a versão exata de
Spring Boot/Java a usar (seguir a do `profile_service`), nem que o `ON DUPLICATE KEY UPDATE` + FK do MySQL se comporta como
descrito sob corrida com a projeção do perfil — isso entra nos testes da Fase 4.
