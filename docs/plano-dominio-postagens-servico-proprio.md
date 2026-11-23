# Plano — Domínio Postagens: `post_service` (postagens, respostas, curtidas e mídia: imagens e GIFs)

Data: 2026-11-14 · Complementa `analise-backend-e-migracao-spring-boot.md`, `plano-dominio-perfil-servico-proprio.md` e
`plano-dominio-relacoes-grafo-social.md`.

> **Estado (2026-11-15): implementado — Fases M1, M2 e 1 a 8 (estado e pendências em §16.1).** O que **não** foi verificado: nada rodou com Docker
> (subida real do RustFS e teste `S3MediaStorageIntegrationTest`, Caddyfile, build das imagens, fluxo no navegador). Base: leitura de `BackEnd/`
> (`PostagemController`, `PostagemService`, `PostagemDao`, `CurtidaController`, `CurtidaDao`, `ImagemController`, `ImagemUtil`,
> `NotificationDao/Controller/Service`, `App`, `infra/mysql/schema.sql`), do `social_graph_service/` e do `profile_service/`
> (eventos que o novo serviço vai consumir) e do frontend (`postagemService`, `curtidaService`, `useCurtidas`, `usePostagens`,
> `usePostagemActions`, `usePostagemInteracoes`, `useNumeroRespostas`, `PostSecondary`, `Notification`, `lib/dates`, `lib/imageUrl`).
> Premissa herdada: **não existem dados reais em nenhum banco** (sem importação de postagens, curtidas ou imagens).
> As decisões estão em **§14 (D1–D18) e estão todas fechadas** (2026-11-15).
>
> **Para implementar em outro contexto, comece pelo §16 (guia de implementação):** estado do repositório, regras do projeto, código de referência, configuração,
> contratos, algoritmos, lista de arquivos do frontend e do legado, pendências herdadas e definição de pronto por fase.
>
> **Atualização (2026-11-15):** o armazenamento de mídia passa a ser o **RustFS** (S3 compatível), as postagens aceitam **GIFs**
> além de imagens (vídeo fica para depois) e a lógica de mídia vira a biblioteca `libs/socialflux-media`, compartilhada com o
> `profile_service` (§9). Mudam as seções 1, 3, 5, 7, 8, 9, 10, 11, 12, 13 e 14.
>
> **Atualização (2026-11-15, 2):** imagens estáticas passam a aceitar **PNG, JPEG e WebP**, com **correção da orientação EXIF**
> (foto de celular em pé não fica deitada), tratamento de JPEG CMYK, recusa de PNG/WebP animados (animação só por GIF) e limite de
> processamentos simultâneos (§9.5). Os mesmos defeitos existiam no `AvatarProcessor` e serão corrigidos na Fase M2.

---

## 1. Resumo executivo

1. Criar o **`post_service`** (Spring Boot 4 · Java 21 · PostgreSQL próprio, porta 8084), no mesmo padrão dos outros serviços:
   resource server JWT, Flyway, outbox/inbox pela biblioteca `socialflux-events`, erros `{ mensagem, sucesso, codigo }`.
2. Ele passa a ser dono de **postagens, respostas (postagem com pai), curtidas e a mídia das postagens (imagens e GIFs)**. Esses quatro
   são um domínio só no legado (a curtida tem FK para a postagem, a resposta é uma postagem, a imagem fica na pasta da postagem) e
   separá-los criaria pontes temporárias entre serviços.
   A **mídia** (validação, processamento e armazenamento no **RustFS**) vive numa biblioteca compartilhada, `libs/socialflux-media`,
   que o `profile_service` também passa a usar (fotos de perfil); cada serviço mantém a sua regra de acesso (§9).
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
7. Estimativa: **≈ 31–37 dias úteis** para uma pessoa (§12), já com a biblioteca de mídia, o RustFS, os GIFs e o processamento de imagem completo. Ao final, saem do legado as postagens, curtidas e imagens **e a
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
(idempotente); contadores de curtidas e respostas; imagens de postagem (validação, processamento, armazenamento no RustFS e
entrega autenticada), com suporte a **GIFs animados**; cópia local de perfis e de relações; eventos `post.*`; limpeza ao excluir conta.

**Fora (agora):** notificações (continuam no legado; o frontend ainda as cria, como no D14 do grafo); busca por texto de
postagens; **vídeos** (exigem transcodificação e armazenamento pesado; é quando um serviço de mídia isolado passa a compensar); reposts/compartilhamento; menções e hashtags; lista de "quem curtiu"; edição de postagem (o legado não tem).

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
    image_key varchar(80) unique,                               -- po_<uuid>.<ext>, objeto no bucket do RustFS (gerado no servidor)
    image_kind varchar(5) check (image_kind in ('IMAGE', 'GIF')),
    image_width smallint, image_height smallint,                -- evitam "pulo" de layout no frontend
    likes_count integer not null default 0 check (likes_count >= 0),
    replies_count integer not null default 0 check (replies_count >= 0),
    created_at timestamptz not null,
    constraint post_has_content check (body is not null or image_key is not null),  -- corrige LOG-08
    constraint post_image_consistent check ((image_key is null) = (image_kind is null)),
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
segurança — o do grafo **já existe** (`social_graph_service/.../service/CounterReconciler`, com testes) e serve de modelo; o de Postagens entra na Fase 6.

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
| Conteúdo | Texto (≤ 1.000 caracteres, sem ser só espaços) **e/ou** uma imagem ou um GIF (um anexo por postagem); pelo menos um dos dois (`post_has_content`) |
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

## 8. API pública (D4)

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

Postagem: `{ id, legenda, imagem: { url, tipo: "IMAGE"\|"GIF", largura, altura } \| null, criado_em, autor: {id, accountId, username, nome, foto_perfil, foto_url, privado}, pai: {id, autor_username} | null,
curtidas, curtido, respostas }`. Listas: `{ itens, proximoCursor }` (limite padrão 20, máximo 100, cursor opaco por chave).

Erros (`{ mensagem, sucesso:false, codigo }`): `postagem_nao_encontrada` 404 (inclui "existe mas não posso ver") ·
`postagem_vazia` 422 · `texto_muito_longo` 422 · `imagem_invalida` 422 · `imagem_animada` 422 · `gif_invalido` 422 · `imagem_muito_grande` 413 · `servidor_ocupado` 503 · `conta_privada` 403 (lista de um
perfil privado) · `muitas_requisicoes` 429 · `parametro_invalido` 400 · `origem_invalida` 403 · `perfil_indisponivel` 503.

**Mudança no frontend (contrato):** `Postagem.id` passa de `number` para `string`; datas deixam de ser array e viram ISO-8601
(`lib/dates.ts`); `Curtida` e `curtidaService.buscar/contar/remover(id)` somem; os hooks por card (`useCurtidas`,
`useNumeroRespostas`) passam a ler `curtidas`, `curtido` e `respostas` da própria postagem; feed/lista/respostas ganham "carregar
mais"; a cadeia de pais usa `/ancestrais` em vez da recursão embutida; `lib/imageUrl.ts` passa a usar `imagem.url`; o card mostra GIFs animados com `<img>` normal e reserva o espaço pela `largura`/`altura`.

---

## 9. Mídia: RustFS, biblioteca `socialflux-media`, imagens e GIFs (D2, D8, D9, D15–D18)

### 9.1 Armazenamento: RustFS
- **RustFS** (objeto compatível com S3) como armazenamento de mídia de todo o projeto: container `rustfs` no compose, volume próprio,
  **rede interna** (sem porta publicada no host), buckets **privados** e credenciais por serviço com acesso só ao bucket dele.
  Bucket de postagens: `post-media` (chave `po_<uuidv7>.<ext>`, gerada no servidor); o do perfil: `avatars`.
- O cliente é o **SDK S3** com endpoint configurável e acesso por caminho (*path-style*): trocar o RustFS por MinIO ou S3 é só
  configuração (D17: dependência aprovada). O RustFS 1.0 foi anunciado como GA em setembro de 2026, com licença
  Apache 2.0, mas a própria documentação diz que a compatibilidade com S3 **não é idêntica** à da AWS; a imagem/tag oficial, o
  modo de criação de buckets e a cobertura das políticas de acesso precisam ser confirmados na primeira subida (D18).
- Testes sem Docker: a biblioteca traz uma implementação em memória de `MediaStorage`.

### 9.2 Biblioteca `libs/socialflux-media` (D15)
Extrai do `profile_service` (`AvatarProcessor`, `LocalDiskStorage`) e generaliza:

| Peça | Conteúdo |
| --- | --- |
| `MediaStorage` | Interface (`put`, `get`, `delete`); implementações **S3/RustFS** e **em memória** (testes) |
| `ImageProcessor` | Imagens estáticas PNG, JPEG e WebP: valida, **corrige a orientação EXIF**, reduz e **re-codifica** (JPEG ou PNG), sem nenhum metadado. Detalhes em 9.5 |
| `GifSanitizer` | Veja 9.3 |
| `MediaKeys` | Gera as chaves (`po_`, `av_`) e extrai o tipo |

O `profile_service` migra para a biblioteca **sem mudar comportamento** (mesmos testes verdes); os avatares passam do disco para o
bucket `avatars` (sem migração de dados) e continuam **públicos e imutáveis** (D8 do perfil).

### 9.3 GIFs (D16)
Re-codificar um GIF para JPEG **mataria a animação**, e o Java não grava GIF animado de forma simples. Por isso o GIF segue outro
caminho, de **validação e regravação**, sem redimensionar:
- Um leitor próprio percorre os blocos do arquivo (cabeçalho `GIF87a`/`GIF89a`, tela lógica, tabelas de cores, quadros, extensões
  de controle e o fim `0x3B`) e **recusa** o que estiver malformado.
- **Limites:** tamanho do arquivo, dimensões, **número de quadros** e pixels decodificados totais (largura × altura × quadros),
  para impedir arquivos que parecem pequenos e explodem em memória. Valores definidos na D16: 8 MB, 1.280 px no maior lado e 300 quadros.
- A regravação mantém só quadros, tempos, transparência e o laço de repetição; descarta comentários, extensões de aplicação
  desconhecidas e **qualquer byte depois do fim do GIF** (arquivos poliglotas).
- Servido com `Content-Type: image/gif`, `X-Content-Type-Options: nosniff` e a mesma regra de acesso das imagens. Vídeo (MP4/WebM)
  **não é aceito**: o validador recusa qualquer coisa que não seja imagem estática ou GIF.

### 9.4 Entrega e privacidade (D8)
O navegador **nunca** fala com o RustFS. `GET /media/posts/{chave}` passa pelo `forward_auth` do gateway; o `post_service` localiza a
postagem pela `image_key` (única), confere a visibilidade (dono, autor público ou aresta "eu sigo o autor") e **repassa os bytes** lidos
do bucket, ou responde **404** (igual a "não existe"). Cabeçalhos: `Cache-Control: private`, `ETag` igual à chave (imutável; a revalidação
refaz a checagem e devolve 304 sem ler o objeto), `nosniff` e `Content-Type` fixo.
- Conta pública que vira privada: as imagens antigas passam a ser restritas na próxima requisição.
- Deixar de seguir: perde o acesso quando o `follow.removed.v1` chega (ms a poucos segundos); uma cópia já em cache no navegador
  só some na revalidação.
- URLs assinadas do RustFS **não** são usadas agora: valeriam para quem tivesse o link e a assinatura S3 depende do endereço, o que
  complica o roteamento pelo Caddy.
- A exclusão da postagem apaga o objeto **depois do commit**; falha ao apagar só gera log (LOG-05). Uma rotina remove objetos sem
  postagem (órfãos de falha entre gravar o objeto e a linha).

### 9.5 Processamento de imagens estáticas (D9)

**Formatos aceitos:** PNG, JPEG e **WebP**. **Limites** (configuráveis): 5 MB, 16 megapixels, lado maior de saída ≤ 1.600 px.
O arquivo enviado **nunca é guardado**: a imagem é decodificada e um arquivo novo é gerado a partir dos pixels.

| # | Passo | O que impede |
| --- | --- | --- |
| 1 | Confere o tamanho em bytes | Upload gigante |
| 2 | Confere os *magic bytes* (PNG, JPEG, `RIFF…WEBP`), ignorando nome e `Content-Type` do cliente | Arquivo qualquer renomeado |
| 3 | Lê **só o cabeçalho** (largura, altura) e, no PNG/WebP, detecta animação | Decodificar sem saber o tamanho |
| 4 | Recusa se `largura × altura` passar do limite | *Image bomb* |
| 5 | Lê a **orientação EXIF** (valores 1 a 8) do arquivo original | Foto de celular que aparece deitada |
| 6 | Decodifica em memória (JPEG CMYK/YCCK é convertido para RGB) | Formatos que o Java puro não lê |
| 7 | **Aplica a orientação nos pixels** (giro e/ou espelhamento) | — |
| 8 | Reduz mantendo a proporção até o lado maior ≤ 1.600 px (nunca amplia) | Imagens enormes servidas a cada visualização |
| 9 | Codifica de novo **sem metadados**: JPEG (qualidade 0,85) quando opaca; PNG quando há transparência | EXIF (inclui GPS), perfis embutidos, bytes após o fim do arquivo |
| 10 | Grava no RustFS com chave gerada no servidor e guarda tipo, **largura e altura já corrigidas** (trocadas nas orientações 5 a 8) | Nome ou caminho controlado pelo cliente |

**Orientação (o ajuste pedido).** O Java não aplica a orientação EXIF e, ao regravar, descarta o campo: sem correção, uma foto tirada
em pé sai deitada. O `ImageProcessor` lê o valor **antes** de descartar o EXIF, gira/espelha os pixels (valores 2 a 8; 1 e ausente = nada
a fazer) e grava a imagem sempre na orientação normal. O valor vem do segmento EXIF do JPEG e, quando existir, do chunk EXIF do
WebP e do PNG. Valor inválido ou ilegível é tratado como "sem orientação", sem recusar o arquivo.

**WebP.** Entra como formato de **entrada**; a saída continua sendo JPEG ou PNG, então **não é preciso um codificador de WebP**. O leitor
vem do TwelveMonkeys ImageIO (`imageio-webp`; ver D17). WebP com transparência vira PNG.

**Outras lacunas tratadas:**
- **JPEG CMYK/YCCK** (comum em arquivos de gráfica) falhava no leitor padrão do Java; o módulo `imageio-jpeg` do TwelveMonkeys o lê e converte para RGB.
- **PNG animado (APNG) e WebP animado** são **recusados** (`imagem_animada`, "para animações, envie um GIF"): a alternativa de usar só o primeiro
  quadro faria a animação sumir sem aviso.
- **Memória:** uma imagem de 16 MP ocupa ~64 MB decodificada. A biblioteca limita os **processamentos simultâneos** (padrão 4,
  `socialflux.media.max-concurrent-processing`); quem não consegue vaga em ~5 s recebe 503 `servidor_ocupado`, e o serviço não cai.
- Imagens sem transparência nunca viram PNG (seria muito maior); com transparência nunca viram JPEG (perderia o alfa).
- O **avatar** usa o mesmo processador no modo "recorte quadrado 256×256", com a mesma correção de orientação.

**Testes do processador (fazem parte da Fase M1):** as 8 orientações EXIF em JPEG (conferindo a cor de cantos conhecidos depois do giro e as
dimensões gravadas); WebP estático com e sem transparência; WebP e PNG animados recusados; JPEG CMYK; arquivo falso `.png`; dimensões
declaradas gigantes; arquivo truncado; ausência de bytes EXIF/GPS na saída; redução de imagem grande e **não** ampliação de pequena;
saturação do limite de processamentos simultâneos.

## 10. Eventos (RabbitMQ, exchange `socialflux.events`)

Produz (outbox transacional; sem dados pessoais — só ids, datas e um trecho do texto):

| Evento | Quando | `data` |
| --- | --- | --- |
| `post.created.v1` | postagem ou resposta criada | `postId, authorId, parentId \| null, parentAuthorId \| null, mediaKind (IMAGE\|GIF\|null), excerpt (≤ 120 caracteres), createdAt` |
| `post.deleted.v1` | excluída (inclusive em cascata) | `postId, authorId` |
| `post.liked.v1` | curtida criada | `postId, postAuthorId, userId` |
| `post.unliked.v1` | curtida removida | `postId, postAuthorId, userId` |

Consome (idempotente por inbox; ignora versões antigas e tipos desconhecidos): `profile.created|updated|privacy-changed|deleted.v1`
(cópia dos autores; a exclusão apaga o conteúdo da conta) e `follow.created|removed.v1` (cópia das arestas, §4).

O `excerpt` existe para o futuro serviço de Notificações montar "curtiu seu post: …" sem consultar este serviço (D11).

---

## 11. Transição com o legado

0. **Mídia (antes de tudo):** o RustFS e a biblioteca `socialflux-media` entram primeiro; o `profile_service` migra os avatares para o
   bucket `avatars` (a rota pública `/media/avatars/*` continua a mesma para o frontend).
1. **Gateway:** `/api/postagens*` e `/media/posts/*` → `post_service` (com `forward_auth`), antes do `/api/*` do legado. A rota
   legada `/api/img/{id}/{arquivo}` sai junto com as classes de postagem.
2. **Frontend:** adaptado ao novo contrato (§8) **antes** de remover o legado.
3. **Legado (Fase 8):** remove `PostagemController/Service/Dao`, `CurtidaController/Service/Dao`, `ImagemController`, `ImagemUtil`
   (e o volume `legacy_images` do compose),
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
| `libs/socialflux-media/` (novo) | Biblioteca de mídia (§9.2) com README e `docs/ARCHITECTURE.md` | dependências: SDK S3 (**D17**) |
| `profile_service/` | Passa a usar a biblioteca e o bucket `avatars` (sem mudança de comportamento) | contrato inalterado |
| `post_service/` (novo) | Serviço completo, Dockerfile, README, `docs/ARCHITECTURE.md`, testes | dependências (as mesmas do grafo) |
| `docker-compose.yml` | `rustfs` (rede interna, volume `rustfs_data`) e criação dos buckets; `post_service` (perfil `apps`, 8084) e `post_db_init` (banco `post`); variáveis `POST_*` e do RustFS; sai o volume `legacy_images` na Fase 8 e `profile_avatars` na migração dos avatares | **sim** |
| `infra/caddy/Caddyfile` | `/api/postagens*` e `/media/posts/*` → `post_service` (com `forward_auth`); remover `/api/img` do legado ao final | **sim** |
| `infra/mysql/schema.sql` | Fase 8: remove `postagem`, `curtida` e `relacao`; `notification.id_post` → `VARCHAR(36)` | **sim** (esquema) |
| `.env.example` | Credenciais e buckets do RustFS, `POST_*` | — |
| `FrontEnd/` | Novo contrato (§8), paginação, `imagem`, GIFs, datas ISO | contrato de API: **sim** |
| `BackEnd/` | Remoção (§11.3) e ajuste de `notification.id_post` | — |
| `social_graph_service/` | Remover a projeção legada (§11.5) | — |
| Docs/`CLAUDE.md`/`scripts/dev/run-post.*` | Atualizados em cada fase | — |

| Fase | Entrega | Pronto quando | Dias |
| --- | --- | --- | --- |
| 0 | Decisões D1–D18 fechadas | ✔ todas fechadas em 2026-11-15 (§14) | 0,5 |
| M1 | RustFS no compose (buckets, credenciais por serviço) + `libs/socialflux-media`: `MediaStorage` (S3 e memória), `ImageProcessor` (PNG/JPEG/WebP, orientação EXIF, CMYK, limite de simultâneos), `GifSanitizer`, testes | testes da biblioteca verdes: as 8 orientações EXIF saem na posição certa, WebP lido, animados recusados, GIF malformado/bomba de quadros/poliglota e arquivo falso rejeitados; subida real confirma o RustFS | 6–7 |
| M2 | `profile_service` migra para a biblioteca e para o bucket `avatars`; **corrige** a orientação EXIF e aceita WebP nos avatares | 41 testes do perfil verdes + testes novos de orientação e WebP; avatar continua público e imutável | 2 |
| 1 | Esqueleto: Maven/Java 21, Flyway `V1`, resource server (JWKS), `OriginCheckFilter`, erros, Dockerfile, script dev | sobe recusando config incompleta; token do auth aceito, adulterado/`aud` errado recusado | 2 |
| 2 | Cópias locais: `author_snapshot` (`profile.*`) e `follow_edge`/`follow_removed` (`follow.*`), exclusão de conta | evento duplicado/antigo/fora de ordem é inócuo; `profile.deleted` limpa tudo | 3 |
| 3 | Núcleo: criar (texto, resposta), ler, excluir, regras de visibilidade, contadores, listas e feed por cursor, ancestrais | testes de IDOR/privacidade em **cada** rota; paginação estável; LOG-03/05/08 cobertos | 5–6 |
| 4 | Curtidas idempotentes + contadores + concorrência | corridas curtir × descurtir × excluir mantêm os contadores certos | 2 |
| 5 | Mídia nas postagens: anexo (imagem ou GIF) no `POST`, entrega autenticada por visibilidade, `ETag`/304, limpeza de órfãos | link de conta privada, estranho e conta excluída recebem 404; GIF animado chega intacto | 2 |
| 6 | Eventos `post.*`, limpeza ao excluir conta, reconciliador de contadores, limites/rate limit | broker fora do ar não perde evento; reconciliador corrige divergência provocada | 3 |
| 7 | Compose + Caddy + frontend no novo contrato | fluxo no navegador: criar (texto, imagem, GIF), comentar, curtir, excluir, feed, perfil, conta privada, mobile | 4–5 |
| 8 | Limpeza do legado, do grafo (projeção `relacao`) e docs | legado compila; E2E verde; grafo sobe sem MySQL | 2 |

**Total: ≈ 31–37 dias úteis** (≈ 6–8 semanas), contra 24–29 do plano anterior: a biblioteca de mídia, o RustFS, os GIFs e o
processamento completo de imagem (WebP, orientação EXIF, CMYK) somam ~7–9 dias, e a fase de imagens do serviço encolhe de 3–4 para 2. O que mais pesa é a Fase 3 (visibilidade e paginação), a
Fase 7 (frontend) e a M1 (sanitizador de GIF e infraestrutura nova).

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
- **RustFS é recente** (GA em 09/2026) e a compatibilidade S3 não é idêntica à da AWS: a Fase M1 inclui uma subida real e testes
  de ponta a ponta contra ele; o SDK S3 com *endpoint* configurável mantém a saída aberta (MinIO ou S3).
- **Orientação EXIF é fácil de errar** (oito casos, com giro e espelhamento, e largura/altura trocadas em quatro deles): por isso a
  matriz de testes das 8 orientações é obrigatória na M1. O TwelveMonkeys entra como **dependência de leitura de imagem de entrada
  do usuário**: deve ser mantido atualizado e tem suas versões fixadas.
- **O sanitizador de GIF é código de segurança escrito por nós** (leitura binária de arquivo enviado pelo usuário): precisa de testes
  adversariais (truncado, quadros demais, tabelas de cores inconsistentes, bytes após o fim) e limites rígidos de memória.
- **Mais tráfego pelo `post_service`:** as imagens passam pelo serviço (§9.4). Um cache curto da decisão de visibilidade resolve se pesar.
- **`excerpt` nos eventos** leva trecho de texto de contas privadas a consumidores futuros (que só devem entregá-lo ao destinatário).

**Evolução prevista (fora deste plano):** serviço de Notificações consumindo `post.*` e `follow.*`; reposts; busca de texto; *read model* de
feed em Redis se medido; lista de quem curtiu.

---

## 14. Decisões

**Todas fechadas na conversa (2026-11-14/15):**

| # | Decisão |
| --- | --- |
| D1 | PostgreSQL, banco `post` **na mesma instância** (um banco por serviço) |
| D2 (escopo) | Postagens, respostas e curtidas num serviço só, no mesmo banco; **não** há serviço separado de curtidas. *(A mídia ficou com a biblioteca compartilhada: ver D15.)* |
| D8 | Imagens de postagem **entregues pelo serviço, com checagem de visibilidade** (404 para quem não pode ver; bucket privado; sem URLs assinadas por ora) |
| — | Armazenamento de mídia: **RustFS**; formatos: **imagens e GIFs** (vídeo fica para depois) |
| D17(b) | Dependência **TwelveMonkeys ImageIO** (`imageio-webp`, `imageio-jpeg`, `imageio-metadata`) para ler WebP, JPEG CMYK e a orientação EXIF — **aprovada em 2026-11-15** |
| D3 | Id da postagem: **UUIDv7**; o legado alarga `notification.id_post` para `VARCHAR(36)` *(a mudança no `schema.sql` ainda pede confirmação na Fase 8)* |
| D4 | Contrato novo `/api/postagens`: **paginado por cursor**, com **contadores e "curti" embutidos** e datas ISO-8601 |
| D15 | Lógica de mídia numa **biblioteca compartilhada** (`libs/socialflux-media`), usada por perfil e postagens |
| D16 | GIFs **só em postagens**, até **8 MB, 1.280 px e 300 quadros** |
| D17(a) | Dependência **SDK S3 da AWS** para falar com o RustFS — **aprovada em 2026-11-15** |
| D18 | RustFS como **container fora do gateway** (a API S3 só é publicada no **loopback** do host, `127.0.0.1:9000`, para os serviços que rodam na máquina; console desligado), buckets `post-media` e `avatars` privados, credenciais por serviço; imagem/tag e criação de buckets confirmadas na Fase M1. **Implementado (2026-11-15):** serviço `rustfs` e `rustfs_init` (buckets `post-media` e `avatars`) no `docker-compose.yml`; credenciais por serviço continuam pendentes da subida real. **Verificado na documentação (2026-11-15):** imagem `rustfs/rustfs:latest` (a doc não fixa tag), S3 na porta 9000 e console na 9001, dados em `/data`, processo não-root (uid 10001), health em `/health`, chave de acesso só com `A–Z` e `0–9`, endereçamento *path-style* por padrão, criação de bucket, Range, listagem e URLs assinadas listados como testados. **Não verificado em execução** (Docker parado): subida real, criação de buckets pelo AWS CLI, usuários por serviço (API administrativa com assinatura SigV4) |
| D5 | Feed e privacidade: **cópia local das arestas por eventos `follow.*`**, sem chamada ao grafo |
| D6 | Visibilidade **por autor**: dono ou seguidor aceito vê conta privada; a resposta é filtrada pelo seu autor; o pai precisa ser visível para responder |
| D7 | Excluir postagem **apaga as respostas e as curtidas** (cascata), como o legado |
| D10 | Limites: texto ≤ 1.000 caracteres; 60 postagens/h e 600 curtidas/h por usuário, configuráveis |
| D14 | Fim da transição: **remover postagens/curtidas/imagens do legado e a projeção `relacao` do grafo** na Fase 8 (inclui mudar o `schema.sql`, que ainda pede confirmação) |
| D11 | Notificações durante a transição: **continuam criadas pelo frontend** (como no D14 do grafo); os eventos `post.*` (com `excerpt`) já nascem prontos para o serviço de Notificações, o próximo domínio |
| D12 | Exclusão de conta: **apagar postagens (e respostas dependentes), curtidas, arestas e a mídia do usuário** |
| D13 | Contexto de resposta: **`pai` resumido nas listas + `GET /{id}/ancestrais`** (até 10 níveis) |
| D9 | Imagens estáticas: **PNG, JPEG e WebP** (entrada), até 5 MB/16 MP; saída JPEG ou PNG (se houver transparência), lado maior ≤ 1.600 px, **com correção da orientação EXIF**; PNG/WebP animados recusados (animação só por GIF) |

O WebP, a orientação EXIF e as demais lacunas de imagem estão **pedidos e incorporados** (D9 fechada); a dependência D17(b) está **aprovada**.
**Nada bloqueia o início:** a Fase M1 (mídia) pode começar. Já estão aprovados o serviço `rustfs` no `docker-compose.yml` (D18) e as
dependências SDK S3 e TwelveMonkeys (D17). Continuam pedindo confirmação (CLAUDE.md, regra 5) o serviço `post_service` no compose, a rota no
`Caddyfile` e as mudanças de `schema.sql` do legado (Fases 7 e 8). **Todas as decisões D1–D18 estão fechadas.**

## 15. O que não foi verificado
Nada foi executado. As afirmações sobre o RustFS (GA em 09/2026, Apache 2.0, S3 compatível, URLs assinadas e políticas no estilo IAM) vêm de buscas na web e da documentação do projeto, e precisam ser confirmadas na primeira subida real; não verifiquei a imagem Docker oficial. A contagem de chamadas por card vem da leitura dos hooks, não de medição no navegador. Não confirmei o
comportamento do Jetty/Javalin com caminhos adversariais em `/api/img` (já tratado como explorável). O ajuste do frontend para o
novo contrato (id string, datas ISO) foi estimado pelos arquivos que citam `Postagem`/`data_postagem`/`getUserImageUrl`, sem alterá-los.
Estimativas de prazo são ordem de grandeza.

## 16. Guia de implementação (para quem for implementar em outro contexto)

Esta seção reúne, em um só lugar, tudo o que uma sessão nova precisa para implementar este plano **sem depender da conversa que o originou**.
Leia, nesta ordem: este §16, o `CLAUDE.md` da raiz, o `README.md` e os `docs/` de cada app em que for mexer (regra do projeto) e, como modelo de código,
os arquivos listados em 16.3.

### 16.1 Estado do repositório (2026-11-15)

- **Não há repositório remoto utilizável:** não abrir PRs nem fazer `push`. Trabalha-se em **branches locais empilhados**; um branch novo parte do branch
  atual (nunca da `main`) quando depende do trabalho anterior. Nunca commitar na `main`.
- **Pilha de branches (da base para o topo):**

  | Branch | Conteúdo |
  | --- | --- |
  | `docs/arquitetura-e-claude-md` | `CLAUDE.md`, `ARCHITECTURE.md` por app, plano de Relações |
  | `refactor/modulo-eventos` | `libs/socialflux-events`, `pom.xml` agregador, auth e profile migrados para a biblioteca |
  | `feat/grafo-social` | `social_graph_service`, rota e compose do grafo, frontend de relações, `total` de solicitações pendentes |
  | `docs/plano-postagens` | este plano |
  | `feat/modulo-midia` | `rustfs` e `rustfs_init` no compose, `.env.example` e docs de infra, mais o guia §16. **A Fase M1 (biblioteca) ainda não começou** |
  | `feat/reconciliador-grafo` | `CounterReconciler` do `social_graph_service` (contadores conferidos por hora), com testes e docs |
  | `feat/biblioteca-midia` | **M1:** `libs/socialflux-media` (ImageProcessor, GifSanitizer, MediaStorage S3/memória, limitador), 43 testes + 1 manual |
  | `feat/perfil-midia` | **M2:** `profile_service` na biblioteca e no bucket `avatars` (orientação EXIF e WebP nos avatares); sai o volume `profile_avatars` |
  | `feat/post-service` | **Fases 1–6:** `post_service` completo (60 testes), Dockerfile, scripts `run-post.*`, docs |
  | `feat/postagens-integracao` | **Fase 7:** `post_db_init`/`post_service` no compose, rotas no Caddyfile, frontend no contrato `/api/postagens` |
  | `refactor/limpeza-legado-postagens` (**atual**) | **Fase 8:** postagens/curtidas/imagens/relações fora do `BackEnd/`, `notification.id_post` → `VARCHAR(36)`, `schema.sql` reduzido, sem `legacy_images`; grafo sem a projeção `relacao` (sem MySQL) |

- **Testes** (`mvn clean test` na raiz, depois da Fase 8): `socialflux-events` 8, `socialflux-media` 43 (+1 manual pulado sem RustFS), `authentication_service` 31,
  `profile_service` 43, `social_graph_service` 48 (os 4 do `LegacyProjectionTest` saíram com a projeção), `post_service` 60 = **233**. Antes: 132.
  Nenhuma fase deste plano pode reduzir esses números; o contrato dos serviços existentes não muda (a M2 só corrige orientação EXIF/WebP nos avatares).
- **Mudanças locais que NÃO devem ser commitadas** (known issue do CLAUDE.md): `BackEnd/logs/app.log`, `BackEnd/.idea/vcs.xml`, `BackEnd/dependency-reduced-pom.xml`.
- O `.env` local do desenvolvedor é gitignored e **ainda não tem** `RUSTFS_ACCESS_KEY` nem `RUSTFS_SECRET_KEY` (o compose exige: copie de `.env.example`).
- **Implementado (2026-11-15).** Pendências e itens não verificados:
  1. **Nada rodou com Docker** (estava parado): subida do RustFS e `S3MediaStorageIntegrationTest`, validação do Caddyfile por um binário do Caddy,
     `docker compose --profile apps up -d --build`, filas no RabbitMQ e o fluxo no navegador (criar texto/imagem/GIF, comentar, curtir, excluir, feed,
     perfil, conta privada, mobile). O `docker compose config` valida o compose.
  2. **Credencial raiz do RustFS** nos dois serviços (D18: credencial por bucket pendente).
  3. **Volumes antigos:** o MySQL precisa ser recriado (`schema.sql` novo, `notification.id_post` texto) e o `post_service` não tem bootstrap de perfis/arestas
     anteriores a ele (crie contas novas ou recrie os volumes em desenvolvimento).
  4. **Corrida rara documentada:** uma resposta criada no exato instante em que um ancestral é excluído pode ficar sem o seu `post.deleted.v1`.
  5. `BackEnd/` tem um `.git` próprio (repositório antigo aninhado): rode o Git sempre a partir da raiz.

### 16.2 Regras do projeto que valem para toda a implementação

| Regra | Como aplicar aqui |
| --- | --- |
| Idioma | Comentários, mensagens de erro/log (`mensagem`), documentação e **commits em pt-BR**. Em `post_service/` e nas libs, **nomes de classes/métodos/variáveis em inglês** (como `profile_service`). `FrontEnd/` e `BackEnd/` usam nomes em português. JSON da API pública em português/snake_case (`foto_perfil`, `{ mensagem, sucesso, codigo }`) |
| Commits | Conventional Commits em pt-BR, `tipo(escopo): descrição no imperativo`; **um commit por tarefa concluída**; docs no mesmo commit da mudança. Escopos: `postagens`, `midia`, `perfil`, `frontend`, `legado`, `infra`, `grafo`. Terminar a mensagem com a linha de coautoria que a ferramenta indicar |
| Documentação no mesmo commit | Arquivo novo/movido → `docs/ARCHITECTURE.md` do app; rota/evento/variável/tabela nova → `README.md` do app (+ `.env.example` e `README.md` da raiz se transversal); gateway/compose/scripts → `docs/ARCHITECTURE.md` da raiz; componente do frontend → `FrontEnd/docs/COMPONENTS.md` |
| Testes | `mvn -pl <serviço> -am test` na raiz após cada implementação em serviço Spring; frontend: `npm run typecheck`, `npm run lint`, `npm run build`. Sem `any`, sem `console.log`/`System.out.println` |
| Banco | Mudança de esquema = **nova migração Flyway**, nunca editar uma existente. Um banco por serviço; **nenhum serviço lê o banco de outro** |
| Identidade | Sempre do `sub` do JWT; ids em caminho/corpo só são conferidos. Nenhuma rota aceita autor, `id_usuario` ou id de curtida do cliente |
| Eventos | Tipo versionado como routing key (`post.created.v1`), gravados no outbox **na mesma transação**; consumidores idempotentes (inbox) e tolerantes a tipos desconhecidos e `aggregateVersion` antigo; **nunca dados pessoais** |
| Segurança de upload | Nome gerado no servidor, *magic bytes*, tamanho, re-codificação, nunca confiar em nome/`Content-Type` do cliente |
| **Perguntar antes de** | adicionar dependências (as aprovadas estão em 16.4), mudar `docker-compose.yml` ou `Caddyfile` (aprovado: só `rustfs`/`rustfs_init`), mudar `schema.sql`, mudar autenticação/token, mudar contrato de evento/API |
| Docker | PostgreSQL, MySQL, RabbitMQ e RustFS **sempre via Docker**. Nunca `docker compose down -v`, `docker volume rm` nem apagar banco sem perguntar |

### 16.3 Código de referência (copiar o padrão, não reinventar)

| Para fazer… | Copie o padrão de… |
| --- | --- |
| Esqueleto do serviço (pom, `application.yml`, `ClockConfig`, `*Properties` validadas) | `social_graph_service/` (`pom.xml`, `config/GraphProperties`, `application.yml`) |
| Resource server JWT, `OriginCheckFilter`, erros | `social_graph_service/.../security/*` e `.../web/{ApiError,ApiException,GlobalExceptionHandler}` |
| Cópia local de perfis por eventos (`author_snapshot`), aplicação idempotente por `aggregateVersion`, túmulo, exclusão de conta | `social_graph_service/.../service/ProfileSyncService`, `.../domain/SnapshotRepository`, `.../events/*` |
| Escrita transacional com contadores + evento no outbox | `social_graph_service/.../service/FollowWriter` |
| Trava por par, paginação por cursor (keyset), `FOR SHARE`/`FOR UPDATE` para fechar corridas | `social_graph_service/.../domain/{FollowRepository,Cursor,SnapshotRepository}` e `GraphService` |
| Limite de ações por hora (rate limit) | `social_graph_service/.../service/RateLimiter` |
| Testes: Postgres embutido (zonky), JWKS local, JWT RS256 real, usuários por evento, corridas | `social_graph_service/src/test/.../support/AbstractGraphTest`, `Http`, `TestSupportConfig`, `ConcurrencyTest`, `SecurityTest`, `ProfileSyncTest` |
| Outbox/inbox/relay/RabbitMQ | `libs/socialflux-events` (README e `docs/ARCHITECTURE.md`); filas do consumidor: `social_graph_service/.../events/Amqp` |
| Validação e re-codificação de imagem, armazenamento | `profile_service/.../storage/{AvatarProcessor,StorageService,LocalDiskStorage,AvatarKeys}` e `service/AvatarService`, `web/MediaController` |
| Dockerfile com contexto na raiz, script `run-*.ps1/.sh`, entradas de compose/Caddy | `social_graph_service/Dockerfile`, `scripts/dev/run-graph.*`, blocos `social_graph_service`/`graph_db_init` do `docker-compose.yml` e o bloco `@graph` do `Caddyfile` |
| Frontend: service tipado, hooks com paginação, modal/lista com "carregar mais" | `FrontEnd/src/services/relacaoService.ts`, `hooks/{useRelacao,useListaRelacoes,useRelacoesPendentes,useModalAberto}`, `components/modals/ModalListUsers` |

### 16.4 Dependências aprovadas e configuração

**Dependências aprovadas (D17):** SDK S3 da AWS (módulo `s3`, mais um cliente HTTP leve) e TwelveMonkeys ImageIO (`imageio-webp`, `imageio-jpeg`,
`imageio-metadata`). **Fixe as versões** nos `pom.xml` (nada de `LATEST`/faixa) e confirme a versão corrente no Maven Central ao adicionar. Qualquer
outra dependência nova pede aprovação. Spring Boot é o do `pom` dos outros serviços (parent `spring-boot-starter-parent` 4.1.2, Java 21).

**Biblioteca `libs/socialflux-media`** (`com.socialflux:socialflux-media`, pacote `com.socialflux.media`, registrada em `<module>` no `pom.xml` agregador,
auto-configurada como a de eventos). Propriedades sugeridas `socialflux.media.*`:

| Propriedade | Padrão | Observação |
| --- | --- | --- |
| `endpoint` | — | `http://rustfs:9000` (containers) ou `http://localhost:9000` (host); obrigatória quando `enabled=true` |
| `region` | `us-east-1` | a documentação do RustFS não exige região; o SDK precisa de uma |
| `access-key` / `secret-key` | — | `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY`; **path-style obrigatório** (`forcePathStyle`) |
| `image.max-bytes` / `image.max-pixels` / `image.max-side` | 5 MB / 16.000.000 / 1.600 | D9 |
| `gif.max-bytes` / `gif.max-side` / `gif.max-frames` | 8 MB / 1.280 / 300 | D16 |
| `max-concurrent-processing` | 4 | semáforo; sem vaga em ~5 s → 503 `servidor_ocupado` |

API mínima da biblioteca: `MediaStorage { put(bucket, key, bytes, contentType); get(bucket, key) -> bytes/stream + contentType; delete(bucket, key); exists }`
(implementações `S3MediaStorage` e `InMemoryMediaStorage` para testes sem Docker), `ImageProcessor`, `GifSanitizer`, `MediaKeys` (`po_<uuidv7>.<ext>`,
`av_<uuidv7>.<ext>`). O `profile_service` hoje usa `av_<uuid>.jpg` e `foto_url=/media/avatars/<chave>`: **esse contrato público não muda**.

**`post_service`** — pasta `post_service/`, pacote `com.socialflux.post`, `artifactId post-service`, porta **8084**, banco **`post`**, eventos `post.*`:

| Variável | Padrão | Observação |
| --- | --- | --- |
| `POST_DB_URL` / `POST_DB_NAME` | `jdbc:postgresql://localhost:5432/post` / `post` | |
| `POST_DB_USER` / `POST_DB_PASSWORD` | caem em `IDENTITY_DB_USER` / `IDENTITY_DB_PASSWORD` | como `profile` e `graph` |
| `AUTH_JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE` | iguais aos outros serviços | só token de **usuário** (sem rotas `/internal`) |
| `RABBITMQ_HOST/PORT/USER/PASSWORD`, `EVENTS_EXCHANGE`, `POST_EVENTS_ENABLED`, `POST_OUTBOX_INTERVAL` | idem `GRAPH_*` | `socialflux.events.mandatory=false` |
| `POST_ALLOWED_ORIGINS` | vazio (= própria origem) | `OriginCheckFilter` |
| `POST_MAX_BODY` | 1000 | D10 |
| `POST_RATE_POSTS_PER_HOUR` / `POST_RATE_LIKES_PER_HOUR` | 60 / 600 | D10; 429 `muitas_requisicoes` com `Retry-After` |
| `POST_MEDIA_BUCKET` | `post-media` | criado por `rustfs_init` |
| `RUSTFS_ENDPOINT`, `RUSTFS_ACCESS_KEY`, `RUSTFS_SECRET_KEY`, `RUSTFS_REGION` | ver acima | |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | 8 MB / 9 MB | cobre o maior anexo (GIF de 8 MB); os limites finos ficam na biblioteca |

Também: `post_service/Dockerfile` (contexto na raiz, copiando `libs/socialflux-events` **e** `libs/socialflux-media` antes do serviço — ajustar o `.dockerignore`
da raiz se preciso), `scripts/dev/run-post.ps1/.sh` (instalam as duas bibliotecas antes do `spring-boot:run`), `post_db_init` no compose, dependência
`rustfs_init: service_completed_successfully`, e as variáveis `POST_*` no `.env.example`.

### 16.5 Contratos detalhados

**Postagem** (resposta de `POST`, `GET {id}` e itens das listas):
```json
{
  "id": "0192f3c4-7a1e-7b2d-9c10-5e8f4a1b2c3d",
  "legenda": "texto da postagem",
  "imagem": { "url": "/media/posts/po_0192….jpg", "tipo": "IMAGE", "largura": 1200, "altura": 800 },
  "criado_em": "2026-11-15T14:03:22.123456Z",
  "autor": { "id": 1001, "accountId": "…uuid…", "username": "ana", "nome": "Ana", "foto_perfil": "av_….jpg", "foto_url": "/media/avatars/av_….jpg", "privado": false },
  "pai": { "id": "…uuid…", "autor_username": "bia" },
  "curtidas": 3, "curtido": true, "respostas": 2
}
```
`legenda` e `imagem` podem ser `null` (nunca os dois). `pai` é `null` em postagem raiz. **Lista:** `{ "itens": [ …postagens… ], "proximoCursor": "…" | null }`.
**Curtida:** `{ "curtido": true, "curtidas": 4 }`. **Ancestrais:** `{ "itens": [ …da mais antiga à mais próxima… ] }`.

| Rota | Sucesso | Erros |
| --- | --- | --- |
| `POST /api/postagens` (multipart: `legenda`, `imagem` arquivo, `pai` UUID) | 201 + postagem | 422 `postagem_vazia`, 422 `texto_muito_longo`, 422 `imagem_invalida` / `imagem_animada` / `gif_invalido`, 413 `imagem_muito_grande`, 404 `postagem_nao_encontrada` (pai inexistente **ou invisível**), 429, 503 `perfil_indisponivel` (autor sem cópia local) / `servidor_ocupado` |
| `GET /api/postagens/{id}` | 200 | 404 `postagem_nao_encontrada` (inexistente ou invisível, igual) |
| `DELETE /api/postagens/{id}` | 204 | 404 (inexistente/invisível); **403 `forbidden`** só se a postagem é visível mas de outro autor |
| `GET /api/postagens/feed?cursor&limite` | 200 | 400 `parametro_invalido` (limite fora de 1–100, cursor adulterado) |
| `GET /api/postagens?autor&filtro&cursor&limite` | 200 | 404 `usuario_nao_encontrado`, 403 `conta_privada`, 400 |
| `GET /api/postagens/{id}/respostas` · `/ancestrais` | 200 | 404 se a postagem não é visível |
| `PUT` / `DELETE /api/postagens/{id}/curtida` | 200 (idempotente) | 404 se não visível; 429 |
| `GET /media/posts/{chave}` | 200 (bytes) / 304 | 404 (chave desconhecida **ou** postagem invisível), 401 pelo gateway sem sessão |

Parâmetros: `limite` 1–100 (padrão 20); `filtro` ∈ `posts` (só raízes), `interacoes` (raízes + respostas do autor), `midia` (só com anexo); `autor` = UUID da conta
ou id inteiro legado. O cursor é opaco (`created_at` + id, base64url), como no grafo.

**Eventos publicados** (`aggregateId` = id da postagem; `aggregateVersion` = 1; só ids/datas/trecho de texto):

| Tipo | `data` |
| --- | --- |
| `post.created.v1` | `postId, authorId, parentId \| null, parentAuthorId \| null, mediaKind (IMAGE\|GIF\|null), excerpt (≤ 120), createdAt` |
| `post.deleted.v1` | `postId, authorId` (um por postagem removida, inclusive em cascata) |
| `post.liked.v1` / `post.unliked.v1` | `postId, postAuthorId, userId` (só quando a curtida de fato mudou) |

**Eventos consumidos** (fila `post-service.profile-events` ligada a `profile.#`; fila `post-service.follow-events` ligada a `follow.#`; DLQ da biblioteca):
`profile.created|updated|privacy-changed|deleted.v1` e `follow.created.v1` / `follow.removed.v1`. O payload de `follow.*` está em
`social_graph_service/README.md` (`relationId, followerId, followeeId, origin|reason, createdAt`).

### 16.6 Algoritmos e detalhes técnicos que não podem ficar de fora

**A. Orientação EXIF (valores da tag 0x0112) → transformação aplicada aos pixels**

| Valor | Significado | Operação | Troca largura/altura? |
| --- | --- | --- | --- |
| 1 (ou ausente/inválido) | normal | nada | não |
| 2 | espelhada na horizontal | espelhar horizontalmente | não |
| 3 | girada 180° | girar 180° | não |
| 4 | espelhada na vertical | espelhar verticalmente | não |
| 5 | transposta | espelhar na diagonal principal (transpor) | **sim** |
| 6 | girada 90° horário | girar 90° horário | **sim** |
| 7 | transversa | espelhar na diagonal secundária | **sim** |
| 8 | girada 90° anti-horário | girar 90° anti-horário | **sim** |

Teste obrigatório: gerar uma imagem com cores diferentes nos quatro cantos, gravá-la como JPEG com cada valor de orientação (inserindo o segmento APP1/EXIF
manualmente no teste) e conferir **a cor dos cantos e as dimensões** depois do processamento. A saída nunca contém EXIF.

**B. Sanitizador de GIF** (`GifSanitizer`): ler o arquivo por **blocos**, sem decodificar LZW:
1. Cabeçalho `GIF87a`/`GIF89a` (6 bytes) e *Logical Screen Descriptor* (largura, altura, flags, cor de fundo, proporção); tabela de cores global se a flag indicar.
   Recusar largura/altura 0 ou acima do limite (`gif.max-side`).
2. Repetir até o trailer `0x3B`: `0x21` extensão (`0xF9` *Graphic Control* — manter; `0xFF` aplicação — manter **somente** `NETSCAPE2.0`/`ANIMEXTS1.0` com o
   bloco de laço; `0xFE` comentário e `0x01` texto — descartar; demais — descartar), `0x2C` imagem (descritor de 9 bytes, tabela local opcional, *LZW minimum code size*
   entre 2 e 8, sub-blocos de dados terminados por `0x00`). Qualquer outro byte de introdutor → recusar (`gif_invalido`).
3. Contar quadros; recusar acima de `gif.max-frames`; recusar se `largura × altura × quadros` ultrapassar um teto de pixels decodificados (sugestão: 256 milhões);
   recusar arquivo truncado (sem trailer), sub-bloco que ultrapassa o fim do arquivo, tabela de cores maior que o arquivo e GIF sem nenhum quadro.
4. Regravar só os blocos mantidos, **terminar no primeiro `0x3B`** (bytes depois dele são descartados — poliglotas) e validar o tamanho final ≤ `gif.max-bytes`.
5. Gravar com `Content-Type: image/gif`. Não redimensionar, não decodificar pixels (a validação estrutural basta; a bomba de descompressão é contida pelo teto de pixels).
Testes: GIF válido de 1 e de vários quadros, laço de repetição preservado, comentário descartado, bytes após o trailer descartados, truncado, quadros demais, tabela de cores
inconsistente, extensão desconhecida, arquivo `.gif` que na verdade é PNG, GIF de 0×0, tamanho acima do limite.

**C. Criar postagem** (ordem importa para manter a transação curta e não deixar lixo): (1) validar corpo e anexo; (2) **processar a mídia fora da transação**
(decodificar/re-codificar, ou sanitizar o GIF); (3) enviar o objeto ao bucket com a chave gerada; (4) abrir a transação: se for resposta, ler o pai com `FOR SHARE`,
conferir que existe e é visível para o autor; inserir a postagem; `replies_count + 1` no pai; gravar `post.created.v1` no outbox; (5) se a transação falhar, **apagar o objeto**
(compensação) — e a varredura de órfãos (E) cobre a falha da compensação.

**D. Visibilidade** (`canView(viewer, author)`): `author = viewer OR NOT author.is_private OR EXISTS follow_edge(viewer → author)` e `author.status = 'ACTIVE'`.
Postagem inexistente, de autor `DELETED` e invisível respondem **404 igual**. Respostas: cada uma é filtrada pelo **seu** autor; para abrir/ listar respostas, a postagem-pai
precisa ser visível. Curtir exige postagem visível. **Ancestrais:** CTE recursiva por `parent_id`, no máximo 10 níveis, parando no primeiro nível invisível.

**E. Rotinas agendadas** (`@Scheduled`, via `@EnableScheduling` como nos outros serviços): (1) podar `follow_removed` com mais de 7 dias; (2) **varredura de órfãos de mídia** a cada
hora: listar objetos do bucket com mais de 1 h e apagar os cuja chave não está em `post.image_key`; (3) **reconciliador de contadores** a cada hora: recalcular `likes_count` e
`replies_count` por `count(*)` e corrigir divergência (registrar quantas foram corrigidas); (4) limpeza do outbox já vem da biblioteca.

**F. Cópia das arestas de seguir** (consumidor de `follow.*`, idempotente pelo inbox): `created`: ignorar se `relationId` está em `follow_removed`; senão gravar a aresta do par
(se já existir outra, trocar só se `createdAt` do evento for mais novo). `removed`: apagar a aresta **somente se o `relation_id` bater**, e registrar `relationId` em `follow_removed`.
`profile.deleted.v1`: marcar o autor `DELETED` e apagar as arestas dos dois lados e (D12) as postagens, respostas dependentes, curtidas e mídia dele. Não há bootstrap (decisão D9 do grafo): em
desenvolvimento, recriar os volumes.

**G. Excluir postagem** (D7): na transação, travar a postagem (`FOR UPDATE`); coletar por CTE recursiva os ids e `image_key` dela **e de todas as descendentes**; apagar (a FK
`on delete cascade` remove respostas e curtidas); decrementar `replies_count` **só do pai direto** da postagem excluída (as descendentes somem junto, então nenhum outro contador muda);
gravar um `post.deleted.v1` por postagem removida; **depois do commit** apagar os objetos (`TransactionSynchronization.afterCommit`; falha só gera log; a varredura E recolhe o que sobrar).
**Exclusão de conta** segue o mesmo caminho para todas as postagens do usuário, em lotes, sem ficar uma transação gigante.

**H. Curtir/descurtir:** `insert into post_like … on conflict do nothing` → se inseriu, `likes_count + 1` e `post.liked.v1`; `delete from post_like … returning` → se removeu,
`likes_count - 1` e `post.unliked.v1`; repetir não muda nada e devolve o estado atual. Pode curtir a própria postagem.

**I. Entrega da mídia** (`GET /media/posts/{chave}`): procurar a postagem por `image_key` (índice único) → `canView` → ler do bucket e repassar; `Content-Type` do que foi gravado,
`X-Content-Type-Options: nosniff`, `Cache-Control: private, max-age=31536000, immutable`, `ETag` = chave; com `If-None-Match` igual, **responder 304 sem ler o objeto**
(a checagem de visibilidade continua sendo feita). O navegador **nunca** acessa o RustFS.

### 16.7 Frontend (contrato `/api/postagens`)

Arquivos que citam o modelo antigo e precisam mudar (`FrontEnd/src/…`): `types/api.ts` (`Postagem.id: string`, `criado_em` ISO, `imagem`, contadores; remover `Curtida`,
`LocalDateTimeArray` quando não houver mais uso — as notificações do legado ainda usam; conferir antes), `services/postagemService.ts` e `services/curtidaService.ts` (substituir pelo contrato
novo; o serviço de curtida some ou vira parte do de postagem), `hooks/usePostagens.ts` (paginação por cursor + "carregar mais"), `hooks/useCurtidas.ts` (usa `curtido`/`curtidas` da própria postagem e
`PUT/DELETE …/curtida`), `hooks/useNumeroRespostas.ts` (usa `respostas`; pode ser removido), `hooks/usePostagemActions.ts` (multipart com `pai` UUID; `useExcluirPostagem`),
`hooks/usePostagemInteracoes.ts`, `lib/dates.ts` (de array do Jackson para ISO-8601; `calcTempPost`/`formatDate` recebem `string`), `lib/imageUrl.ts` (postagem usa `imagem.url`;
avatar continua `foto_url`/`/media/avatars`), `components/postagens/{Postagem,PostSecondary,ListarPostagens,PostActions}.tsx` (ids string; reservar espaço com `largura`/`altura`; GIF com `<img>` normal;
a cadeia de pais passa a usar `GET /{id}/ancestrais` em vez da recursão embutida), `components/forms/{FormCriarPostagem,FormComentar}.tsx` e `modals/ModalImagemComentario.tsx`
(`accept="image/png,image/jpeg,image/webp,image/gif"`, validação de tamanho no cliente só como conveniência, mensagens dos novos códigos de erro),
`pages/{Home,Perfil/PerfilPostagens,PostPage,CriarPostagem}` (feed `GET /api/postagens/feed`, perfil `GET /api/postagens?autor=&filtro=`, rota `/postagem/:id` com id string),
`components/notifications/Notification.tsx` (o link `/postagem/${id_post}` passa a receber string UUID) e `types` de notificação (`id_post: string | null`).
Regras: sem `any`; chamadas só em `services/`; estado em hooks; componentes < ~300 linhas; Bootstrap e tokens de `theme.css`; ícones novos em `lib/icons.ts`; atualizar `FrontEnd/docs/ARCHITECTURE.md`
e `COMPONENTS.md` no mesmo commit; responsivo/mobile-first. Notificações de curtida/comentário **continuam criadas pelo frontend** (D11), agora com o id UUID da postagem.

### 16.8 Legado e grafo: o que remover/alterar na Fase 8 (só depois do frontend novo estar em uso)

**`BackEnd/`:** apagar `PostagemController/Service/Dao`, `CurtidaController/Service/Dao`, `ImagemController`, `util/ImagemUtil`, entidades `Postagem` e `Curtida`, as rotas em `App.java` e as chaves em `Keys`;
**também** (pendência herdada, ver 16.9) `RelacaoController/Service/Dao`, `Relacao`, `UsuarioService`/`UsuarioDao` (só serviam o "em comum") e as 5 rotas `/api/relacao*`.
**Notificações permanecem:** alterar `NotificationController` (hoje `(Integer) json.get("id_post")`), `NotificationService` (`getInt("id_post")`), `Notification` (`Integer id_post`) e `NotificationDao` para tratar `id_post` como **texto (UUID)**.
**`infra/mysql/schema.sql`** (**pede confirmação**): remover `postagem`, `curtida` e `relacao`; `notification.id_post` de `INT` para `VARCHAR(36)`. `schema.sql` só roda na criação do volume do MySQL: avisar para recriar o volume em desenvolvimento (**nunca** apagar sem perguntar).
**Compose/Caddyfile** (**pedem confirmação**): remover o volume `legacy_images`; no `Caddyfile` não há o que remover para o legado: `/api/img/{id}/{arquivo}` simplesmente deixa de existir no `BackEnd/` (e `/api/img/usuarios/*`, do perfil, continua no bloco do `profile_service`); só se acrescentam as rotas novas do `post_service`.
**`social_graph_service/`:** remover o pacote `legacy/` (`LegacyRelacaoWriter`, `LegacyProjectionHandler`, `LegacyProjectionListener`), a fila `social-graph.legacy-projection` e a ligação `follow.#` correspondente em `events/Amqp`, o `LegacyProjectionTest`, a propriedade `graph.legacy.*`,
a dependência `mysql-connector-j` e `h2` de teste (se só servia a esse teste), e as variáveis `GRAPH_LEGACY_ENABLED`/`LEGACY_DB_*` do compose, do `.env.example` e dos scripts `run-graph.*`. O serviço passa a **não depender do MySQL**. Atualizar README/ARCHITECTURE do grafo.
**`profile_service/`:** a projeção `usuario` no MySQL **continua** (Notificações ainda faz JOIN em `usuario`) até Notificações migrar. O volume `profile_avatars` sai na M2, quando os avatares passam ao RustFS.

### 16.9 Pendências herdadas e itens não verificados

1. **Nada foi executado com Docker** desde a criação do grafo: validar o **Caddyfile** (nunca foi validado por um binário do Caddy; sintaxe revisada à mão), a rota `/api/relacoes*`, as filas do RabbitMQ (`social-graph.profile-events`, `social-graph.legacy-projection`, DLQ), o build `docker compose --profile apps up -d --build` (Dockerfiles com contexto na raiz) e o fluxo de relações no navegador (seguir, solicitar, aceitar, recusar, listas paginadas, tooltip do total de solicitações, remover seguidor, excluir conta).
2. **RustFS não foi executado:** confirmar imagem/tag (fixar `RUSTFS_TAG`), se o `curl` existe na imagem (o health check do compose o usa), se o `rustfs_init` cria os buckets e a permissão do volume nomeado (o processo é uid 10001).
3. **Credenciais por serviço no RustFS** (D18) ainda **não existem**: hoje só a credencial raiz. A documentação do RustFS descreve criar usuários e políticas restritos a um bucket pela API administrativa (requisições assinadas SigV4; ver as páginas de IAM e políticas em `docs.rustfs.com`). Implementar e testar na M1 com o RustFS no ar; até lá, registrar que os serviços usam a raiz.
4. **Grafo:** o reconciliador de contadores **já existe** (modelo para o de Postagens); o limite de seguidos não é aplicado ao aceitar solicitação (manual ou automática); sem bootstrap de usuários anteriores ao serviço.
5. **Legado:** as rotas `/api/relacao*` continuam no `BackEnd/` sem uso (remover na Fase 8, ver 16.8).
6. **Gateway/segurança herdados:** o `profile_service`, o `social_graph_service` e o futuro `post_service` só validam assinatura e expiração do JWT; a **revogação imediata** depende do `forward_auth` do Caddy: nunca expor esses serviços fora do gateway.
7. **Aprovações ainda necessárias antes de mexer:** serviço `post_service` e `post_db_init` no `docker-compose.yml`; rotas `/api/postagens*` e `/media/posts/*` no `Caddyfile`; alterações de `schema.sql` do legado; qualquer dependência fora da lista de 16.4; qualquer mudança de autenticação/token ou de contrato de evento/API já publicado (`profile.*`, `follow.*`).

### 16.10 Definição de pronto por fase

| Fase | Pronto quando (além do "pronto quando" da tabela da §12) |
| --- | --- |
| M1 | `libs/socialflux-media` no `pom.xml` agregador; README e `docs/ARCHITECTURE.md` da biblioteca; testes da §9.5 e 16.6 A/B verdes **sem Docker** (armazenamento em memória); teste de integração do `S3MediaStorage` contra o RustFS rodado manualmente e **registrado** (resultado e versão da imagem); `.env.example` e docs de infra atualizados; baseline de 132 testes intacto |
| M2 | `profile_service` usando a biblioteca e o bucket `avatars`; 41 testes anteriores verdes + novos (orientação, WebP); `/media/avatars/{chave}` com o mesmo contrato público; volume `profile_avatars` removido do compose (pede confirmação) |
| 1–2 | Serviço sobe; token válido aceito e adulterado/`aud` errado recusado; eventos duplicados, antigos e fora de ordem inócuos; `profile.deleted` e `follow.removed` tratados; docs do serviço criados |
| 3–4 | Matriz de visibilidade (dono, autor público, seguidor, pedido pendente, estranho) em cada rota; IDOR em cada rota mutável; paginação estável com empates; concorrência (curtir × descurtir × excluir × responder); LOG-03/05/08 cobertos por teste |
| 5 | Anexo (imagem ou GIF) no `POST`; entrega com 404/304 corretos; GIF animado chega intacto; órfãos recolhidos |
| 6 | Eventos `post.*` sem dado pessoal; broker fora do ar não perde evento; reconciliador corrige divergência provocada; limites e 429 testados |
| 7 | Compose, Caddyfile e frontend no novo contrato; `npm run typecheck/lint/build` limpos; fluxo completo no navegador (criar com texto/imagem/GIF, comentar, curtir, excluir, feed, perfil, conta privada, mobile) |
| 8 | Legado, grafo e docs limpos (16.8); `mvn test` da raiz verde; grafo sobe sem MySQL; nenhuma referência restante a `/api/postagem`, `/api/curtida`, `/api/img/{id}/…` ou `Relacao` no frontend e no legado |
