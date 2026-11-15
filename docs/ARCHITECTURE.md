# Arquitetura — visão geral do monorepo

O Social Flux é uma rede social em **migração gradual, um domínio por vez**, de um backend legado (Javalin + MySQL) para
serviços Spring Boot com banco próprio, ligados por eventos no RabbitMQ e expostos por um gateway Caddy na mesma origem.

| Domínio | Onde está hoje |
| --- | --- |
| Identidade e acesso (conta, senha, sessão, e-mail) | `authentication_service/` |
| Perfil (username, nome, bio, foto, privacidade, busca) | `profile_service/` |
| Relações (seguir, solicitações) | `social_graph_service/` (**no compose, no gateway (`/api/relacoes*`) e no frontend**; o legado ainda tem as rotas `/api/relacao`, sem uso, até ser limpo) |
| Postagens/comentários, curtidas, imagens e GIFs de postagem | `post_service/` (**no compose e no gateway (`/api/postagens*`, `/media/posts/*`)**) |
| Notificações | `BackEnd/` (legado) |

Cada aplicação tem o seu próprio `docs/ARCHITECTURE.md`:
[`FrontEnd`](../FrontEnd/docs/ARCHITECTURE.md) · [`authentication_service`](../authentication_service/docs/ARCHITECTURE.md) ·
[`profile_service`](../profile_service/docs/ARCHITECTURE.md) · [`social_graph_service`](../social_graph_service/docs/ARCHITECTURE.md) · [`post_service`](../post_service/docs/ARCHITECTURE.md) · [`libs/socialflux-media`](../libs/socialflux-media/docs/ARCHITECTURE.md) · [`BackEnd`](../BackEnd/docs/ARCHITECTURE.md).
Este arquivo descreve só o que é da raiz e transversal.

## Árvore da raiz

```
social_flux/
├── CLAUDE.md                     regras do projeto para o Claude Code
├── README.md                     visão geral, como rodar e como as peças se falam
├── .env.example                  nomes das variáveis do ambiente (copiar para .env, que não é versionado)
├── .gitignore                    ignora .env, *.pem, node_modules, target/ e arquivos de IDE
├── .dockerignore                 contexto de build da raiz (auth e profile): exclui .git, target, node_modules, segredos, FrontEnd e BackEnd
├── pom.xml                       agregador Maven (só lista módulos): libs/socialflux-events, libs/socialflux-media, authentication_service, profile_service, social_graph_service, post_service
├── docker-compose.yml            ambiente de desenvolvimento completo (perfis `host` e `apps`)
├── docs/                         documentação transversal (este diretório)
├── infra/                        configuração de infraestrutura compartilhada
│   ├── caddy/Caddyfile
│   └── mysql/schema.sql
├── scripts/dev/                  scripts para rodar os backends na máquina com o .env da raiz
├── libs/socialflux-events/       biblioteca compartilhada de eventos (outbox, relay, inbox, RabbitMQ) usada pelos serviços Spring
├── libs/socialflux-media/        biblioteca compartilhada de mídia (imagens PNG/JPEG/WebP com orientação EXIF, GIFs, armazenamento S3/RustFS)
├── FrontEnd/                     SPA React + TypeScript (Vite)
├── authentication_service/       serviço de identidade (Spring Boot + PostgreSQL)
├── profile_service/              serviço de perfil (Spring Boot + PostgreSQL)
├── social_graph_service/         serviço do grafo social: seguir, seguidores, solicitações (Spring Boot + PostgreSQL)
├── post_service/                 serviço de postagens: postagens, respostas, curtidas, imagens e GIFs (Spring Boot + PostgreSQL + RustFS)
├── BackEnd/                      backend legado (Javalin + MySQL)
└── .github/modernize/            artefatos locais da ferramenta "modernize/java-upgrade" (hooks de registro); não fazem parte do sistema
```

## Arquivos da raiz

| Arquivo | O que representa |
| --- | --- |
| `pom.xml` | Agregador Maven: `mvn -pl <serviço> -am test` na raiz testa um serviço compilando as bibliotecas (`libs/socialflux-events`, `libs/socialflux-media`) junto; `mvn test` testa tudo. Não é parent de ninguém; o legado fica de fora |
| `.dockerignore` | Usado pelos builds dos serviços Spring, que têm a raiz como contexto para copiar `libs/socialflux-events` e `libs/socialflux-media` |
| `docker-compose.yml` | Define `postgres` (uma instância, um banco por serviço), `profile_db_init`, `graph_db_init` e `post_db_init` (criam os bancos `profile`, `social_graph` e `post`), `mysql` (legado, inicializado com `infra/mysql/schema.sql`), `rabbitmq`, `mailpit`, `rustfs` (armazenamento de mídia compatível com S3, buckets privados, API só no loopback do host, console desligado) e `rustfs_init` (cria os buckets `post-media` e `avatars`), as seis aplicações (perfil `apps`; `profile_service` e `post_service` esperam o `rustfs_init`), `gateway` (perfil `apps`) e `gateway_host` (perfil `host`, aponta para as apps rodando no host). Volumes nomeados: `postgres_data`, `mysql_data`, `rabbitmq_data`, `rustfs_data`, `legacy_images` (os avatares do `profile_service` ficam no bucket `avatars` do RustFS) |
| `.env.example` | Senhas dos bancos (inclui `GRAPH_DB_NAME` e os limites `GRAPH_*` do grafo), do RabbitMQ e do RustFS (`RUSTFS_ACCESS_KEY` só com `A-Z` e `0-9`), chave JWT RS256, TTLs da sessão, nomes dos cookies, URLs públicas e remetente de e-mail. Só nomes/valores de exemplo |
| `README.md` | Ponto de entrada: tabela das pastas, comandos para subir o ambiente e o diagrama de comunicação |
| `CLAUDE.md` | Contexto, stack, comandos e regras de trabalho para o Claude Code |

## `docs/`

| Arquivo | Conteúdo |
| --- | --- |
| `ARCHITECTURE.md` | Este arquivo |
| `rodando-localmente.md` | Passo a passo para rodar a infra em containers e as apps no host, e tabela de problemas comuns |
| `analise-backend-e-migracao-spring-boot.md` | Análise do backend legado: falhas de segurança (SEC-xx), bugs de lógica (LOG-xx) e o plano de migração para Spring Boot |
| `plano-dominio-identidade-servico-proprio.md` | Plano (implementado) do domínio de identidade: serviço próprio com JWT RS256 em cookies |
| `plano-dominio-perfil-servico-proprio.md` | Plano (implementado) do domínio de perfil e da comunicação entre serviços — decisões D1–D12 |
| `plano-dominio-relacoes-grafo-social.md` | Plano do domínio Relações: `social_graph_service`, análise SQL × NoSQL, seguir/solicitações — decisões D1–D14 (implementado: serviço, compose, gateway e frontend) |
| `plano-dominio-postagens-servico-proprio.md` | Plano (**decisões D1–D18 fechadas, guia no §16; implementado: biblioteca de mídia, perfil no RustFS, `post_service`, compose/gateway e frontend — estado em §16.1**) do domínio Postagens: `post_service` (postagens, respostas, curtidas e mídia: imagens e GIFs no RustFS), biblioteca de mídia compartilhada, feed por cópia local de relações, privacidade imposta no servidor — decisões D1–D18 |
| `plano-dominio-identidade-spring-auth0.md` | Plano **substituído** (Auth0 descartado); mantido só como registro da alternativa avaliada |

## `infra/`

| Arquivo | O que representa |
| --- | --- |
| `caddy/Caddyfile` | Gateway (mesma origem). Remove `X-User-Id`/`X-Account-Id` vindos do cliente; bloqueia `/internal/*` (404); `/auth/*` e `/.well-known/jwks.json` → authentication_service; `/media/avatars/*` → profile_service (público); `/api/usuarios*` e `/api/img/usuarios/*` → profile_service após `forward_auth`; `/api/relacoes*` → social_graph_service após `forward_auth` (sem copiar `X-User-Id`: o serviço usa o `sub` do token); `/api/postagens*` e `/media/posts/*` → post_service após `forward_auth` (as imagens de postagem não são públicas: o cookie vira Bearer e o serviço confere a visibilidade); demais `/api/*` → legado após `forward_auth` (copia `X-User-Id`); o resto → frontend. Converte o cookie de acesso em `Authorization: Bearer`. Os upstreams vêm de variáveis (`AUTH_UPSTREAM`, `PROFILE_UPSTREAM`, `GRAPH_UPSTREAM`, `POST_UPSTREAM`, `LEGACY_UPSTREAM`, `FRONTEND_UPSTREAM`). **Nunca validado por um binário do Caddy** (sintaxe revisada à mão) |
| `mysql/schema.sql` | Esquema do MySQL do legado (`usuario` — projeção do perfil —, `relacao`, `postagem`, `curtida`, `notification`). As tabelas além de `usuario` foram inferidas do código. Só roda na criação do volume |

## `scripts/dev/`

| Arquivo | O que faz |
| --- | --- |
| `load-env.ps1` | Funções PowerShell compartilhadas: carrega o `.env` da raiz no processo (`Import-DotEnv`) e lê variáveis com padrão (`Get-EnvOr`) |
| `run-auth.ps1` / `run-auth.sh` | Instala `libs/socialflux-events` e roda o `authentication_service` no host (porta 8081) com as credenciais do `.env` |
| `run-graph.ps1` / `run-graph.sh` | Instala `libs/socialflux-events` e roda o `social_graph_service` no host (porta 8083), com JWKS do auth e projeção da `relacao` no MySQL ligada |
| `run-post.ps1` / `run-post.sh` | Instala `libs/socialflux-events` e `libs/socialflux-media` e roda o `post_service` no host (porta 8084), com JWKS do auth, RabbitMQ e mídia no RustFS (`localhost:9000`) |
| `run-profile.ps1` / `run-profile.sh` | Instala `libs/socialflux-events` e `libs/socialflux-media` e roda o `profile_service` no host (porta 8082), com JWKS do auth, projeção no MySQL ligada e avatares no RustFS (`localhost:9000`) |
| `run-legacy.ps1` / `run-legacy.sh` | Roda o backend legado no host (porta 8000) em `AUTH_MODE=gateway` |

## Comunicação entre as peças

```
Navegador ─► Caddy :8080 (mesma origem)
              ├─ /                          FrontEnd (nginx :8080 no container / Vite :5173 no host)
              ├─ /auth/*, /.well-known      authentication_service :8081 ─► PostgreSQL (banco identity)
              ├─ /api/usuarios*, /api/img/usuarios/*, /media/avatars/*
              │                             profile_service :8082 ─► PostgreSQL (banco profile) + RustFS (bucket avatars)
              ├─ /api/relacoes*  ─ forward_auth ─►
              │                             social_graph_service :8083 ─► PostgreSQL (banco social_graph)
              ├─ /api/postagens*, /media/posts/*  ─ forward_auth ─►
              │                             post_service :8084 ─► PostgreSQL (banco post) + RustFS (bucket post-media)
              ├─ /api/* (demais) ─ forward_auth ─► authentication_service /internal/auth/verify
              │                └─ X-User-Id ─► BackEnd legado :8000 (rede interna) ─► MySQL
              └─ /internal/*                404

authentication_service ──PUT /internal/profiles/{uuid} (token de serviço)──► profile_service   (cadastro)
authentication_service ──account.registered.v1 | account.deleted.v1──► RabbitMQ ──► profile_service
profile_service ──profile.created|updated|privacy-changed|deleted.v1──► RabbitMQ ──► projeção `usuario` no MySQL
                                                                     ├──► social_graph_service (cópia local dos perfis)
                                                                     └──► post_service (cópia local dos autores)
social_graph_service ──follow.created|removed|requested|request-cancelled|request-rejected.v1──► RabbitMQ ──► projeção `relacao` no MySQL
                                                                                                         └──► post_service (cópia das arestas aceitas)
post_service ──post.created|deleted|liked|unliked.v1──► RabbitMQ ──► (futuro serviço de Notificações)
```

- **Identidade:** conta (auth) e perfil (profile) têm o mesmo UUIDv7 (`sub` do JWT). O id inteiro (`legacy_id`, `X-User-Id`,
  claim `legacy_uid`) é temporário, só para os domínios ainda no legado.
- **Sessão:** JWT RS256 de acesso (cookie `at`/`__Host-at`) + refresh token opaco rotativo (cookie `rt`/`__Secure-rt`, só em `/auth`).
  Os serviços validam o JWT pelo JWKS; a revogação imediata é checada pelo `forward_auth` no gateway.
- **Eventos:** exchange topic `socialflux.events`, routing key = tipo do evento, outbox transacional no produtor, inbox
  idempotente no consumidor, fila de mensagens mortas `socialflux.events.dead` — tudo pela biblioteca `libs/socialflux-events`.
