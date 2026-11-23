# Arquitetura — visão geral do monorepo

O Social Flux é uma rede social em **migração gradual, um domínio por vez**, de um backend legado (Javalin + MySQL, já desligado e removido) para
serviços Spring Boot com banco próprio, ligados por eventos no RabbitMQ e expostos por um gateway Caddy na mesma origem.

| Domínio | Onde está hoje |
| --- | --- |
| Identidade e acesso (conta, senha, sessão, e-mail) | `authentication_service/` |
| Perfil (username, nome, bio, foto, privacidade, busca) | `profile_service/` |
| Relações (seguir, solicitações) | `social_graph_service/` (no compose, no gateway (`/api/relacoes*`) e no frontend) |
| Postagens/comentários, curtidas, imagens e GIFs de postagem | `post_service/` (**no compose, no gateway (`/api/postagens*`, `/media/posts/*`) e no frontend**) |
| Notificações (geradas por eventos, com tempo real por SSE) | `notification_service/` (**no compose, no gateway (`/api/notificacoes*`) e no frontend, com tempo real por SSE**) |

Cada aplicação tem o seu próprio `docs/ARCHITECTURE.md`:
[`FrontEnd`](../FrontEnd/docs/ARCHITECTURE.md) · [`authentication_service`](../authentication_service/docs/ARCHITECTURE.md) ·
[`profile_service`](../profile_service/docs/ARCHITECTURE.md) · [`social_graph_service`](../social_graph_service/docs/ARCHITECTURE.md) · [`post_service`](../post_service/docs/ARCHITECTURE.md) · [`notification_service`](../notification_service/docs/ARCHITECTURE.md) · [`libs/socialflux-media`](../libs/socialflux-media/docs/ARCHITECTURE.md) · [`BackEnd`](../BackEnd/docs/ARCHITECTURE.md).
Este arquivo descreve só o que é da raiz e transversal.

## Árvore da raiz

```
social_flux/
├── CLAUDE.md                     regras do projeto para o Claude Code
├── README.md                     visão geral, como rodar e como as peças se falam
├── .env.example                  nomes das variáveis do ambiente (copiar para .env, que não é versionado)
├── .gitignore                    ignora .env, *.pem, node_modules, target/ e arquivos de IDE
├── .dockerignore                 contexto de build da raiz (auth e profile): exclui .git, target, node_modules, segredos e FrontEnd
├── pom.xml                       agregador Maven (só lista módulos): libs/socialflux-events, libs/socialflux-media, authentication_service, profile_service, social_graph_service, post_service, notification_service
├── docker-compose.yml            ambiente de desenvolvimento completo (perfis `host` e `apps`)
├── docs/                         documentação transversal (este diretório)
├── infra/                        configuração de infraestrutura compartilhada
│   └── caddy/Caddyfile
├── scripts/dev/                  scripts para rodar os backends na máquina com o .env da raiz
├── libs/socialflux-events/       biblioteca compartilhada de eventos (outbox, relay, inbox, RabbitMQ) usada pelos serviços Spring
├── libs/socialflux-media/        biblioteca compartilhada de mídia (imagens PNG/JPEG/WebP com orientação EXIF, GIFs, armazenamento S3/RustFS)
├── FrontEnd/                     SPA React + TypeScript (Vite)
├── authentication_service/       serviço de identidade (Spring Boot + PostgreSQL)
├── profile_service/              serviço de perfil (Spring Boot + PostgreSQL)
├── social_graph_service/         serviço do grafo social: seguir, seguidores, solicitações (Spring Boot + PostgreSQL)
├── post_service/                 serviço de postagens: postagens, respostas, curtidas, imagens e GIFs (Spring Boot + PostgreSQL + RustFS)
├── notification_service/         serviço de notificações: geradas por eventos, agrupadas, com tempo real por SSE (Spring Boot + PostgreSQL)
└── .github/modernize/            artefatos locais da ferramenta "modernize/java-upgrade" (hooks de registro); não fazem parte do sistema
```

## Arquivos da raiz

| Arquivo | O que representa |
| --- | --- |
| `pom.xml` | Agregador Maven: `mvn -pl <serviço> -am test` na raiz testa um serviço compilando as bibliotecas (`libs/socialflux-events`, `libs/socialflux-media`) junto; `mvn test` testa tudo. Não é parent de ninguém |
| `.dockerignore` | Usado pelos builds dos serviços Spring, que têm a raiz como contexto para copiar `libs/socialflux-events` e `libs/socialflux-media` |
| `docker-compose.yml` | Define `postgres` (uma instância, um banco por serviço), `profile_db_init`, `graph_db_init`, `post_db_init` e `notification_db_init` (criam os bancos `profile`, `social_graph`, `post` e `notification`), `rabbitmq`, `mailpit`, `rustfs` (armazenamento de mídia compatível com S3, buckets privados, API só no loopback do host, console desligado) e `rustfs_init` (cria os buckets `post-media` e `avatars`), as aplicações (perfil `apps`; `profile_service` e `post_service` esperam o `rustfs_init`), `gateway` (perfil `apps`) e `gateway_host` (perfil `host`, aponta para as apps rodando no host). Volumes nomeados: `postgres_data`, `rabbitmq_data`, `rustfs_data` (avatares e imagens de postagem ficam nos buckets `avatars` e `post-media` do RustFS) |
| `.env.example` | Senhas dos bancos (inclui `GRAPH_DB_NAME`, os limites `GRAPH_*` do grafo e `NOTIFICATION_DB_NAME` e `NOTIFICATION_*` de Notificações), do RabbitMQ e do RustFS (`RUSTFS_ACCESS_KEY` só com `A-Z` e `0-9`), chave JWT RS256, TTLs da sessão, nomes dos cookies, URLs públicas e remetente de e-mail. Só nomes/valores de exemplo |
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
| `plano-dominio-notificacoes-servico-proprio.md` | Plano (**decisões D1–D16 fechadas; implementado: `notification_service`, compose, gateway, frontend e desligamento do legado — remoção do id inteiro pendente**) do domínio Notificações: notificações geradas por eventos, agrupadas, tempo real por SSE (sessão, revogação, aba líder), retenção, desligamento do legado |
| `plano-dominio-identidade-spring-auth0.md` | Plano **substituído** (Auth0 descartado); mantido só como registro da alternativa avaliada |

## `infra/`

| Arquivo | O que representa |
| --- | --- |
| `caddy/Caddyfile` | Gateway (mesma origem). Remove `X-Account-Id` vindo do cliente; bloqueia `/internal/*` (404); `/auth/*` e `/.well-known/jwks.json` → authentication_service; `/media/avatars/*` → profile_service (público); `/api/usuarios*` e `/api/img/usuarios/*` → profile_service após `forward_auth`; `/api/relacoes*` → social_graph_service após `forward_auth` (sem copiar `X-User-Id`: o serviço usa o `sub` do token); `/api/postagens*` e `/media/posts/*` → post_service após `forward_auth` (as imagens de postagem não são públicas: o cookie vira Bearer e o serviço confere a visibilidade);  `/api/notificacoes*` → notification_service após `forward_auth`, com `flush_interval -1` (o stream SSE não pode ser bufferizado nem comprimido); demais `/api/*` → 404 em JSON (o legado foi desligado); o resto → frontend. Converte o cookie de acesso em `Authorization: Bearer`. Os upstreams vêm de variáveis (`AUTH_UPSTREAM`, `PROFILE_UPSTREAM`, `GRAPH_UPSTREAM`, `POST_UPSTREAM`, `NOTIFICATION_UPSTREAM`, `FRONTEND_UPSTREAM`). |

## `<app>/.github/workflows/`

Cada aplicação (os 5 serviços Spring e o `FrontEnd/`) guarda o seu próprio CI e CD em `<app>/.github/workflows/ci.yml` e `cd.yml`, independentes entre si (sem workflow compartilhado). Atenção: o GitHub só executa workflows de `.github/workflows/` na **raiz do repositório**; estes arquivos são os que rodam nos repositórios isolados de cada aplicação (`social-flux-*`). Nos serviços Spring, o workflow faz checkout do serviço e das libs (`social-flux-lib-events`/`-media`), instala as libs no Maven local e roda o Maven só no serviço (`mvn -f <serviço>/pom.xml test`); o build da imagem monta o layout do monorepo (`libs/*` e `<serviço>/` lado a lado) como contexto.

| Arquivo | O que faz |
| --- | --- |
| `ci.yml` | Em cada **pull request**: lint e, só se passar, testes (`mvn -f <serviço>/pom.xml test`; no frontend `lint` + `typecheck`, depois `build`, pois não há test runner) |
| `cd.yml` | No **push na `main`** (merge): roda os testes de novo; se passarem, publica a imagem no GHCR (`ghcr.io/<dono>/social_flux_<serviço>`, tags `latest` e SHA) e atualiza o container na VPS por SSH (`docker compose pull` + `up -d`) |

A publicação no GHCR usa o `GITHUB_TOKEN` (sem secret). Secrets usados pelo CD: `GHCR_USERNAME` e `GHCR_READ_TOKEN` (PAT só com `read:packages`, para a VPS fazer o pull), `VPS_HOST`, `VPS_USERNAME`, `VPS_SSH_KEY`, `VPS_PORT`, `SSH_PASSPHRASE`, `VPS_PROJECT_PATH`. O `docker-compose` da VPS deve ter serviços com o nome das pastas (`authentication_service`, ..., `frontend`) e `image: ghcr.io/<dono>/social_flux_<serviço>:latest`.

## `scripts/dev/`

| Arquivo | O que faz |
| --- | --- |
| `load-env.ps1` | Funções PowerShell compartilhadas: carrega o `.env` da raiz no processo (`Import-DotEnv`) e lê variáveis com padrão (`Get-EnvOr`) |
| `run-auth.ps1` / `run-auth.sh` | Instala `libs/socialflux-events` e roda o `authentication_service` no host (porta 8081) com as credenciais do `.env` |
| `run-graph.ps1` / `run-graph.sh` | Instala `libs/socialflux-events` e roda o `social_graph_service` no host (porta 8083), com JWKS do auth e RabbitMQ |
| `run-post.ps1` / `run-post.sh` | Instala `libs/socialflux-events` e `libs/socialflux-media` e roda o `post_service` no host (porta 8084), com JWKS do auth, RabbitMQ e mídia no RustFS (`localhost:9000`) |
| `run-notification.ps1` / `run-notification.sh` | Instala `libs/socialflux-events` e roda o `notification_service` no host (porta 8085), com JWKS do auth e RabbitMQ |
| `run-profile.ps1` / `run-profile.sh` | Instala `libs/socialflux-events` e `libs/socialflux-media` e roda o `profile_service` no host (porta 8082), com JWKS do auth e avatares no RustFS (`localhost:9000`) |

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
              ├─ /api/notificacoes* (inclui o stream SSE)  ─ forward_auth ─►
                                            notification_service :8085 ─► PostgreSQL (banco notification)
              ├─ /api/* (demais) ─ forward_auth ─► authentication_service /internal/auth/verify
              └─ /internal/*                404

authentication_service ──PUT /internal/profiles/{uuid} (token de serviço)──► profile_service   (cadastro)
authentication_service ──account.registered.v1 | account.deleted.v1──► RabbitMQ ──► profile_service
profile_service ──profile.created|updated|privacy-changed|deleted.v1──► RabbitMQ
                                                                     ├──► social_graph_service (cópia local dos perfis)
                                                                     └──► post_service (cópia local dos autores)
social_graph_service ──follow.created|removed|requested|request-cancelled|request-rejected.v1──► RabbitMQ
                                                                                                         └──► post_service (cópia das arestas aceitas)
post_service ──post.created|deleted|liked|unliked.v1──► RabbitMQ ──► notification_service (curtidas e respostas)
social_graph_service ──follow.*.v1──► RabbitMQ ──► notification_service (novos seguidores e solicitações)
profile_service ──profile.*.v1──► RabbitMQ ──► notification_service (cópia local de quem age)
notification_service ──SSE (/api/notificacoes/stream)──► navegador (tempo real)
```

- **Identidade:** conta (auth) e perfil (profile) têm o mesmo UUIDv7 (`sub` do JWT), e é o **único** id de usuário no sistema:
  o antigo id inteiro (`legacy_id`, `X-User-Id`, claim `legacy_uid`) foi removido junto com o backend legado.
- **Sessão:** JWT RS256 de acesso (cookie `at`/`__Host-at`) + refresh token opaco rotativo (cookie `rt`/`__Secure-rt`, só em `/auth`).
  Os serviços validam o JWT pelo JWKS; a revogação imediata é checada pelo `forward_auth` no gateway.
- **Eventos:** exchange topic `socialflux.events`, routing key = tipo do evento, outbox transacional no produtor, inbox
  idempotente no consumidor, fila de mensagens mortas `socialflux.events.dead` — tudo pela biblioteca `libs/socialflux-events`.
