# Plano — Domínio Postagens: `post_service` (postagens, respostas, curtidas e imagens de postagem)

Data: 2026-11-14 · Complementa `analise-backend-e-migracao-spring-boot.md`, `plano-dominio-perfil-servico-proprio.md` e
`plano-dominio-relacoes-grafo-social.md`.

> **Estado: proposta para análise. Nada deste plano foi implementado nem executado.** Base: leitura de `BackEnd/`
> (`PostagemController`, `PostagemService`, `PostagemDao`, `CurtidaController`, `CurtidaDao`, `ImagemController`, `ImagemUtil`,
> `NotificationDao/Controller/Service`, `App`, `infra/mysql/schema.sql`), do `social_graph_service/` e do `profile_service/`
> (eventos que o novo serviço vai consumir) e do frontend (`postagemService`, `curtidaService`, `useCurtidas`, `usePostagens`,
> `usePostagemActions`, `usePostagemInteracoes`, `useNumeroRespostas`, `PostSecondary`, `Notification`, `lib/dates`, `lib/imageUrl`).
> Premissa herdada: **não existem dados reais em nenhum banco** (sem importação de postagens, curtidas ou imagens).
> As decisões que dependem de você estão em **§14 (D1–D14)**.

---

## 1. Resumo executivo

1. Criar o **`post_service`** (Spring Boot 4 · Java 21 · PostgreSQL próprio, porta 8084), no mesmo padrão dos outros serviços:
   resource server JWT, Flyway, outbox/inbox pela biblioteca `socialflux-events`, erros `{ mensagem, sucesso, codigo }`.
2. Ele passa a ser dono de **postagens, respostas (postagem com pai), curtidas e imagens de postagem**. Esses quatro
   são um domínio só no legado (a curtida tem FK para a postagem, a resposta é uma postagem, a imagem fica na pasta da postagem) e
   separá-los criaria pontes temporárias entre serviços.
3. **A privacidade de conta privada passa a ser imposta no servidor** (hoje só o frontend esconde; SEC-03): o serviço mantém duas
   cópias locais alimentadas por eventos — o perfil público dos autores (`profile.*`) e as relações aceitas (`follow.*`) — e
   decide a visibilidade de cada postagem sem chamar outro serviço.
4. O **feed** e as listas do perfil passam a ser **paginados por cursor** e cada postagem já vem com **contadores e "curti"**
   embutidos. Hoje cada card dispara 3 chamadas (contar curtidas, buscar a própria curtida, baixar *todas* as respostas só para
   contá-las) e as listas não têm paginação.
5. **Banco: PostgreSQL** (análise em §6). O acesso dominante é "postagens de quem eu sigo, mais novas primeiro", com contadores e
   regras de visibilidade que se resolvem em um JOIN com a cópia local das relações.
6. Corrige no domínio: IDOR ao curtir/descurtir (SEC-02), conta privada exposta (SEC-03), upload sem defesa e servido sem
   autenticação (SEC-04/05), `Integer !=` (LOG-03), postagem só com imagem recusada (LOG-08), exclusão que falha se o arquivo
   sumiu (LOG-05), N+1 e recursão (LOG-09), horário sem fuso (LOG-11).
7. Estimativa: **≈ 24–29 dias úteis** para uma pessoa (§12). Ao final, saem do legado as postagens, curtidas e imagens **e a
   projeção `relacao`** que o grafo mantém hoje só para o feed do legado; sobra no legado apenas o domínio de **Notificações**.

---

## 2. Avaliação do que existe hoje

### 2.1 Backend legado

| Rota legada | O que faz | Problemas |
| --- | --- | --- |
| `POST /api/postagem` (multipart: `legenda`, `imagem`, `id_postagem` pai) | Cria postagem ou resposta; `id_postagem` do formulário vira o pai | Não valida se o pai existe (LOG-08); **imagem sem legenda é recusada** apesar da mensagem de erro dizer o contrário; `id_postagem` não numérico vira 500; `LocalDateTime.now()` no fuso do servidor (LOG-11); imagem validada só pelo nome/`Content-Type` do cliente, copiada crua (SEC-05) |
| `GET /api/postagem?id_user=` (+ `allPosts`, `mediaPosts`) | Postagens de **qualquer** usuário (posts, "com interações", mídia) | **Conta privada exposta a qualquer logado** (SEC-03); sem paginação; consulta com N+1 (LOG-09) |
| `GET /api/postagem?id_seguidor=` | Feed (`JOIN relacao ... aceito = 1`) | `id_seguidor` vem do cliente: **lê o feed de qualquer usuário** (SEC-02); sem paginação |
| `GET /api/postagem?id_postagem=&responses=true` | Respostas de uma postagem | Sem paginação; o frontend baixa **todas** só para contar |
| `GET /api/postagem/{id}` e `?id_postagem=` | Uma postagem | Sem checagem de privacidade; cada postagem carrega o pai **recursivamente**, uma consulta por nível, com o `ResultSet` externo aberto (LOG-09) |
| `DELETE /api/postagem/{id}` | Exclui a própria postagem | Compara `Integer` com `!=` (LOG-03: **usuário com id ≥ 128 não consegue excluir a própria postagem**); postagem inexistente → NPE/500; se o arquivo da imagem não existir, a exclusão **falha** (LOG-05); imagens das respostas filhas ficam órfãs |
| `POST /api/curtida` (`{id_usuario, id_conteudo}`) | Curte | **`id_usuario` vem do corpo: curte em nome de qualquer usuário** (SEC-02); não valida que a postagem existe |
| `DELETE /api/curtida/{id}` | Descurte pelo id da curtida | **Qualquer logado remove a curtida de qualquer um**; resposta `sucesso:false` em caso de sucesso (LOG-06) |
| `GET /api/curtida?id_usuario=&id_conteudo=` | Existe curtida deste usuário? | 200 de corpo vazio quando não existe; ramos placeholder `"Oloco"`/`"Sei lá"` (LOG-06); consulta curtidas de terceiros |
| `GET /api/curtida/numero/{id_conteudo}` | Conta curtidas | Uma chamada por card |
| `GET /api/img/{id_usuario}/{id_imagem}` | Serve a imagem (**compartilha o caminho com as fotos antigas de perfil**) | **Sem autenticação**, sem normalização de caminho (path traversal, SEC-04), `Content-Type` pela extensão |

Esquema atual (`infra/mysql/schema.sql`): `postagem(id AI, imagem, legenda TEXT, data_postagem DATETIME, id_usuario FK, id_postagem FK
auto-referência ON DELETE CASCADE)` e `curtida(id AI, id_usuario FK, id_conteudo FK, UNIQUE(id_usuario, id_conteudo))`.
As imagens ficam em `src/main/resources/public/images/<id_usuario>/<uuid>.<ext>`, **dentro da árvore de código**.

### 2.2 Como o frontend usa o domínio

| Arquivo | Uso |
| --- | --- |
| `services/postagemService.ts` | `criar` (multipart), `remover`, `buscar`, `buscarRespostas`, `buscarFeed(idSeguidor)`, `buscarDoUsuario(id, filtro)` |
| `services/curtidaService.ts` | `criar`, `buscar` (corpo vazio = sem curtida), `remover(idCurtida)`, `contar` |
| `hooks/usePostagens.ts` | `useFeed`, `usePostagensUsuario`, `useRespostas`: carregam **a lista inteira** |
| `hooks/useCurtidas.ts` | Por card: `contar` + `buscar` e, ao curtir, `criar` + notificação criada pelo frontend |
| `hooks/useNumeroRespostas.ts` | Por card: baixa **todas as respostas** só para contar |
| `hooks/usePostagemActions.ts` | `useCriarPostagem` (cria e, se for resposta, notifica o autor) e `useExcluirPostagem` |
| `components/postagens/PostSecondary.tsx` | Card em lista; **mostra a cadeia de postagens-pai** (recursivamente) acima da resposta em foco |
| `pages/Perfil/Perfil.tsx` | `podeVerConteudo` só na UI |
| `components/notifications/Notification.tsx` | Link `/postagem/${notification.id_post}` (o id da postagem vem do legado de notificações) |
| `lib/dates.ts` / `lib/imageUrl.ts` | Data como array do Jackson interpretado como UTC; imagem de postagem em `/api/img/{usuario}/{arquivo}` |
| `types/api.ts` | `Postagem { id:number, imagem, legenda, data_postagem:number[], usuario:Usuario, postagem:Postagem\|null }`, `Curtida` |

**Custo atual por card de postagem:** 3 requisições (contar curtidas, buscar a própria curtida, baixar as respostas). Um feed
de 20 postagens dispara **60+ chamadas**, além da própria lista sem limite.

### 2.3 Acoplamentos com outros domínios

| Quem | Como depende de postagens | Efeito da migração |
| --- | --- | --- |
| **Relações (grafo)** | O feed legado faz `JOIN relacao`; por isso o grafo mantém a projeção `relacao` no MySQL | O novo serviço consome `follow.*` e **a projeção deixa de ser necessária** |
| **Perfil** | JOINs em `usuario` (username, nome, foto) em toda consulta | O novo serviço consome `profile.*` (cópia local, como o grafo) |
| **Notificações (legado)** | `notification.id_post INT`, sem FK; o frontend cria as notificações de curtida/comentário (`POST /api/notification`, forjável) e monta o link `/postagem/{id_post}` | Se o id da postagem deixar de ser inteiro, a coluna precisa mudar (**D3**). Os eventos `post.*` preparam o domínio de Notificações |
| **Imagens de perfil antigas** | O caminho `/api/img/{id}/{arquivo}` ainda existe por compatibilidade | Não há fotos antigas (sem dados); a rota some do legado |

---

## 3. Escopo do `post_service`

**Dentro:** criar postagem e resposta (texto e/ou imagem); ler uma postagem; excluir a própria; feed paginado; postagens de um
usuário (posts, "com interações", mídia) paginadas; respostas paginadas; cadeia de ancestrais de uma resposta; curtir/descurtir
(idempotente); contadores de curtidas e respostas; imagens de postagem (validação, re-codificação, armazenamento e entrega
autenticada); cópia local de perfis e de relações; eventos `post.*`; limpeza ao excluir conta.

**Fora (agora):** notificações (continuam no legado; o frontend ainda as cria, como no D14 do grafo); busca por texto de
postagens; reposts/compartilhamento; menções e hashtags; lista de "quem curtiu"; edição de postagem (o legado não tem).

---

## 4. Dependências entre serviços (como o serviço sabe quem pode ver o quê)

```
profile_service ──profile.created|updated|privacy-changed|deleted.v1──► post_service   (autor: nome, foto, conta privada?)
social_graph_service ──follow.created|removed.v1──────────────────────► post_service   (quem segue quem: feed e privacidade)
post_service ──post.created|deleted|liked|unliked.v1──► RabbitMQ ──► (futuro serviço de Notificações)
```

- **Nenhuma chamada síncrona entre serviços** (continua sem token de serviço novo; D9 do grafo e D10 dele seguem como estão).
  A visibilidade se resolve com um JOIN local em `follow_edge`.
- **A ordem dos eventos do grafo importa**: `follow.removed` pode chegar antes do `follow.created` do mesmo vínculo. Como cada
  vínculo tem `relationId` próprio, o consumidor guarda um registro de vínculos já removidos (§5.1, `follow_removed`) e a regra
  "só vale a aresta mais nova do par".
- **Janela de consistência (a mesma de D6 do plano do perfil):** entre a mudança de privacidade/seguir e o evento chegar (ms a
  poucos segundos) a visibilidade pode estar um passo atrás. Para o conteúdo de contas privadas isso significa que um seguidor
  recém-removido ainda pode ver por instantes (aceitável) e que um recém-aceito ainda não vê.

---

## 5. Modelo de dados (PostgreSQL, Flyway, banco `post`)

### 5.1 Esquema `V1__post.sql`
```sql
-- Cópia local do perfil PÚBLICO dos autores (profile.*). Mesmo formato do social_graph_service.
create table author_snapshot (
    user_id uuid primary key, legacy_id integer unique,        -- legacy_id TEMPORÁRIO (frontend ainda usa o inteiro)
    username varchar(60) not null, display_name varchar(120) not null, avatar_key varchar(80),
    is_private boolean not null default false,
    status varchar(10) not null check (status in ('ACTIVE','DELETED')),
    profile_version bigint not null, updated_at timestamptz not null
);

-- Quem segue quem (follow.*). Só o necessário para o feed e a privacidade; o dono dessa informação é o grafo.
create table follow_edge (
    follower_id uuid not null, followee_id uuid not null,
    relation_id uuid not null,                                  -- vínculo que gerou a aresta (protege contra evento antigo)
    created_at timestamptz not null,
    primary key (follower_id, followee_id)
);
create index idx_follow_edge_followee on follow_edge (followee_id);
create table follow_removed (                                   -- "túmulos" de vínculos removidos (podados após 7 dias)
    relation_id uuid primary key, removed_at timestamptz not null
);

create table post (
    id uuid primary key,                                        -- UUIDv7 (ordenável no tempo)   [D3]
    author_id uuid not null references author_snapshot (user_id),
    parent_id uuid references post (id) on delete cascade,      -- resposta; apagar o pai apaga as respostas   [D7]
    body varchar(1000),
    image_key varchar(80),                                      -- po_<uuid>.jpg, gerado no servidor
    likes_count integer not null default 0 check (likes_count >= 0),
    replies_count integer not null default 0 check (replies_count >= 0),
    created_at timestamptz not null,
    constraint post_has_content check (body is not null or image_key is not null),  -- corrige LOG-08
    constraint post_body_not_blank check (body is null or length(btrim(body)) > 0)
);
create index idx_post_author on post (author_id, created_at desc, id desc) where parent_id is null;      -- aba "Posts"
create index idx_post_author_all on post (author_id, created_at desc, id desc);                            -- "com interações"
create index idx_post_author_media on post (author_id, created_at desc, id desc) where image_key is not null;
create index idx_post_replies on post (parent_id, created_at desc, id desc) where parent_id is not null;
create index idx_post_feed on post (created_at desc, id desc) where parent_id is null;

create table post_like (
    post_id uuid not null references post (id) on delete cascade,
    user_id uuid not null,
    created_at timestamptz not null,
    primary key (post_id, user_id)                              -- "curti?" e unicidade
);
create index idx_post_like_user on post_like (user_id, created_at desc);

create table outbox_event (...);   create table processed_message (...);   -- iguais às dos outros serviços
```

### 5.2 Consultas centrais
```sql
-- Feed: postagens (não respostas) de quem eu sigo, mais novas primeiro, por cursor (created_at, id)
select p.*, a.* from post p
join follow_edge e on e.followee_id = p.author_id and e.follower_id = :eu
join author_snapshot a on a.user_id = p.author_id
where p.parent_id is null and a.status = 'ACTIVE'
  and (p.created_at, p.id) < (:ts, :id)
order by p.created_at desc, p.id desc limit :n;

-- "curti?" para a página inteira, sem N+1
select post_id from post_like where user_id = :eu and post_id = any(:ids);
```
Visibilidade (`podeVer(eu, autor)`) em SQL: `autor = eu OR NOT a.is_private OR exists(follow_edge eu→autor)`.

### 5.3 Contadores
`likes_count` e `replies_count` são atualizados **na mesma transação** da curtida/resposta/exclusão (como o `follow_stats` do
grafo), para a lista devolver tudo em uma consulta. Há um reconciliador agendado (recalcula por `count(*)`) como rede de
segurança — ele **não existe no grafo ainda**; entra nos dois serviços na Fase 6.

### 5.4 Concorrência
Curtir/descurtir usa `insert ... on conflict do nothing` / `delete ... returning`, então repetir não duplica nem desbalanceia o
contador. Excluir uma postagem trava a linha do pai (`select ... for update`) antes de mexer em `replies_count`.

---

## 6. SQL × NoSQL para este domínio

| Necessidade | PostgreSQL | Alternativa NoSQL |
| --- | --- | --- |
| Feed "de quem eu sigo" ordenado e paginado | JOIN indexado com a cópia local das arestas; paginação por chave | Exige **fan-out na escrita** (copiar a postagem para a caixa de cada seguidor — Cassandra/Redis/Mongo) ou consulta em dois passos |
| Contadores consistentes com curtida/resposta | Mesma transação | Contadores atômicos separados do documento; consistência eventual |
| Curtida única por usuário, exclusão em cascata de respostas | PK composta, FK `on delete cascade` | Garantir por código; cascata manual |
| Regra de visibilidade (autor privado × seguidor) | `exists` no mesmo banco | Duplicar `is_private` e as arestas em cada documento/partição |
| Operação | Já existe (mesma instância; um banco por serviço) | Novo container, novo backup, nova linguagem de consulta |

**Veredito: PostgreSQL (D1).** O padrão dominante (feed por *pull* com JOIN local) é eficiente para centenas de milhares de
postagens por usuário seguido e dispensa a complexidade do fan-out. Se algum dia o feed virar gargalo medido, o caminho é um *read
model* de feed por usuário (Redis) alimentado pelos eventos `post.created.v1` — sem trocar a fonte da verdade.

---

## 7. Regras de negócio

| Regra | Detalhe |
| --- | --- |
| Identidade | Sempre do `sub` do token; o `id` na URL é só o alvo. Nenhuma rota aceita autor, `id_usuario` ou id de curtida do cliente |
| Conteúdo | Texto (≤ 1.000 caracteres, sem ser só espaços) **e/ou** imagem; pelo menos um dos dois (`post_has_content`) |
| Resposta | `pai` deve existir **e ser visível para quem responde**; senão 404 uniforme |
| Visibilidade (D6) | Ver a postagem de um autor privado exige ser o dono ou ter aresta aceita para ele. Vale para feed, perfil, postagem avulsa, respostas, ancestrais, curtir **e a imagem** |
| Respostas de postagem visível | Cada resposta é filtrada pela regra do **seu** autor (uma resposta de conta privada some para quem não a segue, sem esconder o resto) |
| Excluir (D7) | Só o autor. Apaga também as respostas (cascata) e as curtidas; a imagem é apagada **depois do commit**, e a falta do arquivo nunca impede a exclusão (LOG-05) |
| Curtir | Idempotente. Só em postagem **visível** para quem curte. Descurtir sem curtida não faz nada |
| Conta excluída (`profile.deleted.v1`, D12) | Apaga as postagens do usuário (e respostas dependentes), as curtidas dele (ajustando contadores alheios), arestas e imagens |
| Conta vira privada/pública | Só atualiza a cópia local; a visibilidade passa a valer na próxima consulta |
| Limites (D10) | 60 postagens/respostas por hora e 600 curtidas/hora por usuário (configuráveis); corpo ≤ 1.000 caracteres |
| Datas | `timestamptz`; a API devolve ISO-8601 em UTC (corrige LOG-11) |

---

## 8. API pública (proposta — D4)

Base `/api/postagens`. `{id}` = UUID da postagem. Autor/usuário em filtros: UUID ou id inteiro legado (`autor`).

| Rota | Quem | Resposta |
| --- | --- | --- |
| `POST /api/postagens` (multipart: `legenda`, `imagem`, `pai`) | logado | `201` com a postagem criada |
| `GET /api/postagens/{id}` | logado, se visível | Uma postagem |
| `DELETE /api/postagens/{id}` | só o autor | `204` |
| `GET /api/postagens/feed?cursor=&limite=` | logado | Postagens de quem sigo (**sem** respostas) |
| `GET /api/postagens?autor=&filtro=posts\|interacoes\|midia&cursor=&limite=` | logado (conta privada: dono ou seguidor) | Postagens de um usuário |
| `GET /api/postagens/{id}/respostas?cursor=&limite=` | logado, se visível | Respostas filtradas pela visibilidade |
| `GET /api/postagens/{id}/ancestrais` | logado, se visível | Cadeia de pais até 10 níveis (da mais antiga à mais próxima), parando no primeiro não visível |
| `PUT /api/postagens/{id}/curtida` | logado, se visível | `200 { curtido: true, curtidas }` (idempotente) |
| `DELETE /api/postagens/{id}/curtida` | logado | `200 { curtido: false, curtidas }` |
| `GET /media/posts/{chave}` | logado, se a postagem for visível | A imagem (D8) |

Postagem: `{ id, legenda, imagem_url, criado_em, autor: {id, accountId, username, nome, foto_perfil, foto_url, privado}, pai: {id, autor_username} | null,
curtidas, curtido, respostas }`. Listas: `{ itens, proximoCursor }` (limite padrão 20, máximo 100, cursor opaco por chave).

Erros (`{ mensagem, sucesso:false, codigo }`): `postagem_nao_encontrada` 404 (inclui "existe mas não posso ver") ·
`postagem_vazia` 422 · `texto_muito_longo` 422 · `imagem_invalida` 422 · `imagem_muito_grande` 413 · `conta_privada` 403 (lista de um
perfil privado) · `muitas_requisicoes` 429 · `parametro_invalido` 400 · `origem_invalida` 403 · `perfil_indisponivel` 503.

**Mudança no frontend (contrato):** `Postagem.id` passa de `number` para `string`; datas deixam de ser array e viram ISO-8601
(`lib/dates.ts`); `Curtida` e `curtidaService.buscar/contar/remover(id)` somem; os hooks por card (`useCurtidas`,
`useNumeroRespostas`) passam a ler `curtidas`, `curtido` e `respostas` da própria postagem; feed/lista/respostas ganham "carregar
mais"; a cadeia de pais usa `/ancestrais` em vez da recursão embutida; `lib/imageUrl.ts` aponta para `imagem_url`.

---

## 9. Imagens de postagem (D8, D9)

- Armazenamento atrás de `StorageService` (disco fora do classpath, volume `post_media`; S3/MinIO depois), chave gerada no servidor:
  `po_<uuidv7>.jpg`. O cliente nunca informa nome ou caminho.
- Validação (mesmo desenho do `AvatarProcessor`): limite de 5 MB e 16 MP (lê o cabeçalho antes de decodificar); *magic bytes*
  PNG/JPEG/WebP; decodifica e **re-codifica** para JPEG (remove EXIF/payload), reduzindo o lado maior a 1.600 px.
- Entrega **autenticada**: `GET /media/posts/{chave}` passa pelo `forward_auth` do gateway e o serviço confere a visibilidade da postagem
  dona da imagem; `Content-Type` fixo, `nosniff`, `Cache-Control: private, max-age=31536000, immutable`. Assim, a imagem de uma
  conta privada não vaza por link compartilhado.
- Apagar a postagem apaga o arquivo **depois do commit** (falha de arquivo só gera log). Um job remove arquivos sem postagem
  (órfãos de falha entre gravar o arquivo e a linha).

---

## 10. Eventos (RabbitMQ, exchange `socialflux.events`)

Produz (outbox transacional; sem dados pessoais — só ids, datas e um trecho do texto):

| Evento | Quando | `data` |
| --- | --- | --- |
| `post.created.v1` | postagem ou resposta criada | `postId, authorId, parentId \| null, parentAuthorId \| null, hasImage, excerpt (≤ 120 caracteres), createdAt` |
| `post.deleted.v1` | excluída (inclusive em cascata) | `postId, authorId` |
| `post.liked.v1` | curtida criada | `postId, postAuthorId, userId` |
| `post.unliked.v1` | curtida removida | `postId, postAuthorId, userId` |

Consome (idempotente por inbox; ignora versões antigas e tipos desconhecidos): `profile.created|updated|privacy-changed|deleted.v1`
(cópia dos autores; a exclusão apaga o conteúdo da conta) e `follow.created|removed.v1` (cópia das arestas, §4).

O `excerpt` existe para o futuro serviço de Notificações montar "curtiu seu post: …" sem consultar este serviço (D11).

---

## 11. Transição com o legado

1. **Gateway:** `/api/postagens*` e `/media/posts/*` → `post_service` (com `forward_auth`), antes do `/api/*` do legado. A rota
   legada `/api/img/{id}/{arquivo}` sai junto com as classes de postagem.
2. **Frontend:** adaptado ao novo contrato (§8) **antes** de remover o legado.
3. **Legado (Fase 8):** remove `PostagemController/Service/Dao`, `CurtidaController/Service/Dao`, `ImagemController`, `ImagemUtil`,
   as entidades `Postagem`/`Curtida` e as rotas de `App.java`; remove `postagem` e `curtida` do `schema.sql`. **Notificações
   permanecem** (usam `usuario`).
4. **`notification.id_post`** (D3): como as postagens passam a ter UUID, a coluna `INT` do legado passa a `VARCHAR(36)` e o
   `NotificationController/Service/Entity` tratam o id como texto. É a única mudança de esquema do legado (precisa da sua aprovação).
5. **Projeção `relacao` do grafo:** sem o feed legado, nada mais lê a tabela `relacao` do MySQL. Na mesma entrega desliga-se
   `GRAPH_LEGACY_ENABLED` e remove-se o pacote `social_graph_service/.../legacy`, a tabela `relacao` do `schema.sql` e o consumidor
   do MySQL (o grafo deixa de depender do MySQL).
6. **Projeção `usuario`:** continua até Notificações migrar (`notification` faz JOIN em `usuario`).

---

## 12. Mudanças por pasta e fases

| Pasta / arquivo | Mudança | Exige seu OK (CLAUDE.md) |
| --- | --- | --- |
| `post_service/` (novo) | Serviço completo, Dockerfile, README, `docs/ARCHITECTURE.md`, testes | dependências (as mesmas do grafo) |
| `docker-compose.yml` | `post_service` (perfil `apps`, 8084), `post_db_init` (banco `post`), volume `post_media`, variáveis `POST_*` | **sim** |
| `infra/caddy/Caddyfile` | `/api/postagens*` e `/media/posts/*` → `post_service` (com `forward_auth`); remover `/api/img` do legado ao final | **sim** |
| `infra/mysql/schema.sql` | Fase 8: remove `postagem`, `curtida` e `relacao`; `notification.id_post` → `VARCHAR(36)` | **sim** (esquema) |
| `FrontEnd/` | Novo contrato (§8), paginação, `imagem_url`, datas ISO | contrato de API: **sim** |
| `BackEnd/` | Remoção (§11.3) e ajuste de `notification.id_post` | — |
| `social_graph_service/` | Remover a projeção legada (§11.5) | — |
| Docs/`.env.example`/`CLAUDE.md`/`scripts/dev/run-post.*` | Atualizados em cada fase | — |

| Fase | Entrega | Pronto quando | Dias |
| --- | --- | --- | --- |
| 0 | Decisões D1–D14 fechadas | registradas neste arquivo | 0,5 |
| 1 | Esqueleto: Maven/Java 21, Flyway `V1`, resource server (JWKS), `OriginCheckFilter`, erros, Dockerfile, script dev | sobe recusando config incompleta; token do auth aceito, adulterado/`aud` errado recusado | 2 |
| 2 | Cópias locais: `author_snapshot` (`profile.*`) e `follow_edge`/`follow_removed` (`follow.*`), exclusão de conta | evento duplicado/antigo/fora de ordem é inócuo; `profile.deleted` limpa tudo | 3 |
| 3 | Núcleo: criar (texto/imagem/resposta), ler, excluir, regras de visibilidade, contadores, listas e feed por cursor, ancestrais | testes de IDOR/privacidade em **cada** rota; paginação estável; LOG-03/05/08 cobertos | 5–6 |
| 4 | Curtidas idempotentes + contadores + concorrência | corridas curtir × descurtir × excluir mantêm os contadores certos | 2 |
| 5 | Imagens: `StorageService`, validação, re-codificação, entrega autenticada, limpeza | arquivo falso `.png`, gigante, traversal e link de conta privada rejeitados | 3–4 |
| 6 | Eventos `post.*`, limpeza ao excluir conta, reconciliador de contadores, limites/rate limit | broker fora do ar não perde evento; reconciliador corrige divergência provocada | 3 |
| 7 | Compose + Caddy + frontend no novo contrato | fluxo no navegador: criar, comentar, curtir, excluir, feed, perfil, conta privada, mobile | 4–5 |
| 8 | Limpeza do legado, do grafo (projeção `relacao`) e docs | legado compila; E2E verde; grafo sobe sem MySQL | 2 |

**Total: ≈ 24–29 dias úteis** (≈ 5–6 semanas). O que mais pesa é a Fase 3 (regras de visibilidade e paginação) e a Fase 7 (frontend).

---

## 13. Testes, riscos e evolução

**Testes:** PostgreSQL real embutido e JWT RS256 de verdade, como nos outros serviços, mais: IDOR em cada rota mutável; matriz de
visibilidade (público, privado + seguidor, privado + pedido pendente, privado + estranho, dono) em postagem, feed, perfil, respostas,
ancestrais, curtir e imagem; resposta a postagem invisível; paginação com empates de data; concorrência (curtir × descurtir,
excluir × curtir, responder × excluir o pai); conta excluída no meio; eventos de relação fora de ordem; upload malicioso.

**Riscos:**
- **Consistência eventual da privacidade** (§4): janela de ms a segundos.
- **Cópia de arestas** (`follow_edge`) duplica parte do grafo: custo de armazenamento pequeno (um par de UUIDs por relação), mas é uma
  segunda fonte que precisa de reconciliação. Mitigação: ordenação por `relationId`, túmulos e (futuro) endpoint de bootstrap.
- **Exclusão em cascata** pode apagar muitas respostas de uma vez; as imagens dessas respostas precisam ser removidas depois do commit.
- **Mudança de contrato no frontend** é a maior do projeto até aqui (id vira string, datas, paginação, hooks por card).
- **`excerpt` nos eventos** leva trecho de texto de contas privadas a consumidores futuros (que só devem entregá-lo ao destinatário).

**Evolução prevista (fora deste plano):** serviço de Notificações consumindo `post.*` e `follow.*`; reposts; busca de texto; *read model* de
feed em Redis se medido; lista de quem curtiu.

---

## 14. Decisões que precisam do seu parecer

| # | Decisão | Recomendado (em **negrito**) · alternativas |
| --- | --- | --- |
| D1 | Banco | **PostgreSQL, banco `post` na instância existente** · documento/colunar (§6) |
| D2 | Escopo do serviço | **Postagens + respostas + curtidas + imagens de postagem num serviço só** · curtidas em serviço separado |
| D3 | Id da postagem | **UUIDv7; alargar `notification.id_post` no legado para `VARCHAR(36)`** · inteiro temporário (`legacy_id`) até Notificações migrar |
| D4 | Contrato HTTP | **Novo `/api/postagens`: paginado, contadores e "curti" embutidos, ISO-8601** · manter `/api/postagem` com regras corrigidas |
| D5 | Feed e privacidade | **Cópia local das arestas por eventos `follow.*` (sem chamada ao grafo)** · consulta síncrona ao grafo (exige token de serviço e API interna) |
| D6 | Visibilidade | **Por autor: dono ou seguidor aceito vê conta privada; resposta filtrada pelo seu autor; pai precisa ser visível para responder** · esconder a thread inteira se a raiz for privada |
| D7 | Excluir postagem | **Apaga as respostas e as curtidas (cascata), como o legado** · manter as respostas como "postagem removida" |
| D8 | Entrega das imagens | **Autenticada com checagem de visibilidade (`/media/posts/{chave}`)** · pública e imutável como os avatares (D8 do perfil) |
| D9 | Processamento de imagem | **PNG/JPEG/WebP até 5 MB/16 MP, re-codificadas para JPEG, lado maior ≤ 1.600 px** · manter formato original (preserva transparência) |
| D10 | Limites | **Texto ≤ 1.000 caracteres; 60 postagens/h e 600 curtidas/h por usuário, configuráveis** · outros valores · sem teto |
| D11 | Notificações durante a transição | **Continuam criadas pelo frontend (como no D14 do grafo); os eventos `post.*` (com `excerpt`) já nascem prontos para o serviço de Notificações, que é o próximo** · antecipar Notificações antes de Postagens |
| D12 | Exclusão de conta | **Apagar postagens (e respostas dependentes), curtidas, arestas e imagens do usuário** · anonimizar e manter o conteúdo |
| D13 | Contexto de resposta | **`pai` resumido nas listas + `GET /{id}/ancestrais` (até 10 níveis)** · embutir a cadeia inteira (volta ao N+1) |
| D14 | Fim da transição | **Remover postagens/curtidas/imagens do legado e a projeção `relacao` do grafo na Fase 8 (inclui mudar o `schema.sql`)** · deixar a projeção até Notificações migrar |

## 15. O que não foi verificado
Nada foi executado. A contagem de chamadas por card vem da leitura dos hooks, não de medição no navegador. Não confirmei o
comportamento do Jetty/Javalin com caminhos adversariais em `/api/img` (já tratado como explorável). O ajuste do frontend para o
novo contrato (id string, datas ISO) foi estimado pelos arquivos que citam `Postagem`/`data_postagem`/`getUserImageUrl`, sem alterá-los.
Estimativas de prazo são ordem de grandeza.
