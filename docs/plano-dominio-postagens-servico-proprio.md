# Plano — Domínio Postagens: `post_service` (postagens, respostas, curtidas e mídia: imagens e GIFs)

Data: 2026-11-14 · Complementa `analise-backend-e-migracao-spring-boot.md`, `plano-dominio-perfil-servico-proprio.md` e
`plano-dominio-relacoes-grafo-social.md`.

> **Estado: proposta para análise. Nada deste plano foi implementado nem executado.** Base: leitura de `BackEnd/`
> (`PostagemController`, `PostagemService`, `PostagemDao`, `CurtidaController`, `CurtidaDao`, `ImagemController`, `ImagemUtil`,
> `NotificationDao/Controller/Service`, `App`, `infra/mysql/schema.sql`), do `social_graph_service/` e do `profile_service/`
> (eventos que o novo serviço vai consumir) e do frontend (`postagemService`, `curtidaService`, `useCurtidas`, `usePostagens`,
> `usePostagemActions`, `usePostagemInteracoes`, `useNumeroRespostas`, `PostSecondary`, `Notification`, `lib/dates`, `lib/imageUrl`).
> Premissa herdada: **não existem dados reais em nenhum banco** (sem importação de postagens, curtidas ou imagens).
> As decisões estão em **§14 (D1–D18)**, com o que já foi fechado na conversa e o que continua aberto.
>
> **Atualização (2026-11-15):** o armazenamento de mídia passa a ser o **RustFS** (S3 compatível), as postagens aceitam **GIFs**
> além de imagens (vídeo fica para depois) e a lógica de mídia vira a biblioteca `libs/socialflux-media`, compartilhada com o
> `profile_service` (§9). Mudam as seções 1, 3, 5, 7, 8, 9, 10, 11, 12, 13 e 14.

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
7. Estimativa: **≈ 29–35 dias úteis** para uma pessoa (§12), já com a biblioteca de mídia, o RustFS e os GIFs. Ao final, saem do legado as postagens, curtidas e imagens **e a
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

Postagem: `{ id, legenda, imagem: { url, tipo: "IMAGE"\|"GIF", largura, altura } \| null, criado_em, autor: {id, accountId, username, nome, foto_perfil, foto_url, privado}, pai: {id, autor_username} | null,
curtidas, curtido, respostas }`. Listas: `{ itens, proximoCursor }` (limite padrão 20, máximo 100, cursor opaco por chave).

Erros (`{ mensagem, sucesso:false, codigo }`): `postagem_nao_encontrada` 404 (inclui "existe mas não posso ver") ·
`postagem_vazia` 422 · `texto_muito_longo` 422 · `imagem_invalida` 422 · `gif_invalido` 422 · `imagem_muito_grande` 413 · `conta_privada` 403 (lista de um
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
  configuração (D17: dependência nova, precisa de aprovação). O RustFS 1.0 foi anunciado como GA em setembro de 2026, com licença
  Apache 2.0, mas a própria documentação diz que a compatibilidade com S3 **não é idêntica** à da AWS; a imagem/tag oficial, o
  modo de criação de buckets e a cobertura das políticas de acesso precisam ser confirmados na primeira subida (D18).
- Testes sem Docker: a biblioteca traz uma implementação em memória de `MediaStorage`.

### 9.2 Biblioteca `libs/socialflux-media` (D15)
Extrai do `profile_service` (`AvatarProcessor`, `LocalDiskStorage`) e generaliza:

| Peça | Conteúdo |
| --- | --- |
| `MediaStorage` | Interface (`put`, `get`, `delete`); implementações **S3/RustFS** e **em memória** (testes) |
| `ImageProcessor` | Valida (*magic bytes* PNG/JPEG/WebP, tamanho, megapixels lidos do cabeçalho antes de decodificar), decodifica e **re-codifica** (remove EXIF/payload), reduz o lado maior; JPEG para opacas, PNG quando há transparência |
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
  para impedir arquivos que parecem pequenos e explodem em memória. Valores sugeridos: 8 MB, 1.280 px no maior lado e 300 quadros.
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
| 0 | Decisões D1–D18 fechadas | registradas neste arquivo | 0,5 |
| M1 | RustFS no compose (buckets, credenciais por serviço) + `libs/socialflux-media`: `MediaStorage` (S3 e memória), `ImageProcessor`, `GifSanitizer`, testes | testes da biblioteca verdes; GIF malformado, bomba de quadros, poliglota e arquivo falso rejeitados; subida real confirma o RustFS | 4–5 |
| M2 | `profile_service` migra para a biblioteca e para o bucket `avatars` | 41 testes do perfil verdes; avatar continua público e imutável | 1–2 |
| 1 | Esqueleto: Maven/Java 21, Flyway `V1`, resource server (JWKS), `OriginCheckFilter`, erros, Dockerfile, script dev | sobe recusando config incompleta; token do auth aceito, adulterado/`aud` errado recusado | 2 |
| 2 | Cópias locais: `author_snapshot` (`profile.*`) e `follow_edge`/`follow_removed` (`follow.*`), exclusão de conta | evento duplicado/antigo/fora de ordem é inócuo; `profile.deleted` limpa tudo | 3 |
| 3 | Núcleo: criar (texto, resposta), ler, excluir, regras de visibilidade, contadores, listas e feed por cursor, ancestrais | testes de IDOR/privacidade em **cada** rota; paginação estável; LOG-03/05/08 cobertos | 5–6 |
| 4 | Curtidas idempotentes + contadores + concorrência | corridas curtir × descurtir × excluir mantêm os contadores certos | 2 |
| 5 | Mídia nas postagens: anexo (imagem ou GIF) no `POST`, entrega autenticada por visibilidade, `ETag`/304, limpeza de órfãos | link de conta privada, estranho e conta excluída recebem 404; GIF animado chega intacto | 2 |
| 6 | Eventos `post.*`, limpeza ao excluir conta, reconciliador de contadores, limites/rate limit | broker fora do ar não perde evento; reconciliador corrige divergência provocada | 3 |
| 7 | Compose + Caddy + frontend no novo contrato | fluxo no navegador: criar (texto, imagem, GIF), comentar, curtir, excluir, feed, perfil, conta privada, mobile | 4–5 |
| 8 | Limpeza do legado, do grafo (projeção `relacao`) e docs | legado compila; E2E verde; grafo sobe sem MySQL | 2 |

**Total: ≈ 29–35 dias úteis** (≈ 6–7 semanas), contra 24–29 do plano anterior: a biblioteca de mídia, o RustFS e os GIFs
somam ~5–7 dias, e a fase de imagens do serviço encolhe de 3–4 para 2. O que mais pesa é a Fase 3 (visibilidade e paginação), a
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
- **O sanitizador de GIF é código de segurança escrito por nós** (leitura binária de arquivo enviado pelo usuário): precisa de testes
  adversariais (truncado, quadros demais, tabelas de cores inconsistentes, bytes após o fim) e limites rígidos de memória.
- **Mais tráfego pelo `post_service`:** as imagens passam pelo serviço (§9.4). Um cache curto da decisão de visibilidade resolve se pesar.
- **`excerpt` nos eventos** leva trecho de texto de contas privadas a consumidores futuros (que só devem entregá-lo ao destinatário).

**Evolução prevista (fora deste plano):** serviço de Notificações consumindo `post.*` e `follow.*`; reposts; busca de texto; *read model* de
feed em Redis se medido; lista de quem curtiu.

---

## 14. Decisões

**Fechadas na conversa (2026-11-14/15):**

| # | Decisão |
| --- | --- |
| D1 | PostgreSQL, banco `post` **na mesma instância** (um banco por serviço) |
| D2 (escopo) | Postagens, respostas e curtidas num serviço só, no mesmo banco; **não** há serviço separado de curtidas. *(A mídia foi reaberta: ver D15.)* |
| D8 | Imagens de postagem **entregues pelo serviço, com checagem de visibilidade** (404 para quem não pode ver; bucket privado; sem URLs assinadas por ora) |
| — | Armazenamento de mídia: **RustFS**; formatos: **imagens e GIFs** (vídeo fica para depois) |

**Abertas — preciso do seu parecer (recomendada em negrito):**

| # | Decisão | Opções |
| --- | --- | --- |
| D15 | Onde mora a lógica de mídia | **Biblioteca `libs/socialflux-media`, usada por perfil e postagens** · serviço de mídia isolado (a regra de acesso por imagem exigiria token de serviço ou URLs assinadas, e criar a postagem viraria dois passos) · tudo dentro do `post_service` (duplica o que o perfil já tem) |
| D16 | GIFs: onde e com que limites | **Só em postagens, até 8 MB, 1.280 px e 300 quadros** · também como foto de perfil · outros limites |
| D17 | Dependência: SDK S3 da AWS (módulo S3) para falar com o RustFS | **Aprovar** · outro cliente S3 (ex.: MinIO Java) |
| D18 | Infra do RustFS | **Container na rede interna, buckets `post-media` e `avatars` privados, credenciais por serviço; confirmar imagem/tag e criação de buckets na Fase M1** · expor o console/porta no host em desenvolvimento |
| D3 | Id da postagem | **UUIDv7; alargar `notification.id_post` no legado para `VARCHAR(36)`** · inteiro temporário (`legacy_id`) até Notificações migrar |
| D4 | Contrato HTTP | **Novo `/api/postagens`: paginado, contadores e "curti" embutidos, ISO-8601** · manter `/api/postagem` com regras corrigidas |
| D5 | Feed e privacidade | **Cópia local das arestas por eventos `follow.*` (sem chamada ao grafo)** · consulta síncrona ao grafo (exige token de serviço e API interna) |
| D6 | Visibilidade | **Por autor: dono ou seguidor aceito vê conta privada; resposta filtrada pelo seu autor; pai precisa ser visível para responder** · esconder a thread inteira se a raiz for privada |
| D7 | Excluir postagem | **Apaga as respostas e as curtidas (cascata), como o legado** · manter as respostas como "postagem removida" |
| D9 | Processamento de imagem estática | **PNG/JPEG/WebP até 5 MB/16 MP; JPEG (ou PNG se houver transparência); lado maior ≤ 1.600 px** · manter o formato original |
| D10 | Limites | **Texto ≤ 1.000 caracteres; 60 postagens/h e 600 curtidas/h por usuário, configuráveis** · outros valores · sem teto |
| D11 | Notificações durante a transição | **Continuam criadas pelo frontend (como no D14 do grafo); os eventos `post.*` (com `excerpt`) já nascem prontos para o serviço de Notificações, o próximo domínio** · antecipar Notificações antes de Postagens |
| D12 | Exclusão de conta | **Apagar postagens (e respostas dependentes), curtidas, arestas e a mídia do usuário** · anonimizar e manter o conteúdo |
| D13 | Contexto de resposta | **`pai` resumido nas listas + `GET /{id}/ancestrais` (até 10 níveis)** · embutir a cadeia inteira (volta ao N+1) |
| D14 | Fim da transição | **Remover postagens/curtidas/imagens do legado e a projeção `relacao` do grafo na Fase 8 (inclui mudar o `schema.sql`)** · deixar a projeção até Notificações migrar |

As abertas que **bloqueiam o início** são D15, D17 e D18 (a Fase M1 depende delas); D3 e D4 bloqueiam a Fase 3; as demais podem seguir a
recomendação se você não se opuser.

## 15. O que não foi verificado
Nada foi executado. As afirmações sobre o RustFS (GA em 09/2026, Apache 2.0, S3 compatível, URLs assinadas e políticas no estilo IAM) vêm de buscas na web e da documentação do projeto, e precisam ser confirmadas na primeira subida real; não verifiquei a imagem Docker oficial. A contagem de chamadas por card vem da leitura dos hooks, não de medição no navegador. Não confirmei o
comportamento do Jetty/Javalin com caminhos adversariais em `/api/img` (já tratado como explorável). O ajuste do frontend para o
novo contrato (id string, datas ISO) foi estimado pelos arquivos que citam `Postagem`/`data_postagem`/`getUserImageUrl`, sem alterá-los.
Estimativas de prazo são ordem de grandeza.
