# Social Flux

| Pasta | O que é |
| --- | --- |
| `authentication_service/` | **Novo** serviço de identidade e acesso (Spring Boot, PostgreSQL, JWT RS256 em cookies HttpOnly) |
| `profile_service/` | **Novo** serviço de perfil (Spring Boot, PostgreSQL próprio, busca, foto de perfil, eventos via RabbitMQ) |
| `social_graph_service/` | **Novo** serviço do grafo social (seguir, seguidores, solicitações; PostgreSQL próprio; no compose, no gateway em `/api/relacoes*` e no frontend) |
| `libs/socialflux-events/` | Biblioteca compartilhada de eventos (outbox, inbox, RabbitMQ) dos serviços Spring Boot |
| `FrontEnd/` | SPA React + TypeScript (Vite) |
| `BackEnd/` | Backend **legado** (Javalin + MySQL), sendo substituído domínio a domínio |
| `infra/` | Caddy (gateway) e esquema MySQL de desenvolvimento |
| `docs/` | Arquitetura geral (`ARCHITECTURE.md`), análise do backend e planos de migração. Cada app tem também o seu `docs/ARCHITECTURE.md` (e o FrontEnd, `docs/COMPONENTS.md`) |
| `docker-compose.yml` | Ambiente de desenvolvimento completo |

## Rodando

```bash
cp .env.example .env              # ajuste as senhas (AUTH_ALLOW_EPHEMERAL_KEY=true dispensa a chave RSA em dev)
```

**A) Só a infraestrutura em containers; as aplicações na sua máquina**
```bash
docker compose --profile host up -d     # postgres, mysql, rabbitmq, rustfs, mailpit + gateway (Caddy) apontando para o host
```
Depois rode as quatro aplicações localmente (auth, perfil, legado e frontend) (comandos em `docs/rodando-localmente.md`) e abra http://localhost:5173.
Sem o perfil `host` (`docker compose up -d`) sobem só os bancos, o RabbitMQ, o RustFS (mídia) e o mailpit.

**B) Tudo em containers**
```bash
docker compose --profile apps up -d --build     # http://localhost:8080
```

- Caixa de e-mail (verificação/redefinição de senha): http://localhost:8025
- Painel do RabbitMQ: http://localhost:15672 (usuário/senha: `RABBITMQ_USER`/`RABBITMQ_PASSWORD` do `.env`)

## Como as peças se falam

```
Navegador ─► Caddy (mesma origem)
              ├─ /                          frontend
              ├─ /auth/*                    authentication_service ─► PostgreSQL (banco identity)
              ├─ /api/usuarios*, /media/*   profile_service ─► PostgreSQL (banco profile) + disco (avatares)
              ├─ /api/relacoes*             social_graph_service ─► PostgreSQL (banco social_graph)
              └─ /api/* (demais) ─ forward_auth ─► authentication_service /internal/auth/verify
                              └─ X-User-Id ─► backend legado (Javalin, AUTH_MODE=gateway, rede interna) ─► MySQL

authentication_service ──PUT /internal/profiles/{uuid}──► profile_service         (cadastro; token de serviço)
authentication_service ──account.registered|deleted.v1──► RabbitMQ ──► profile_service
profile_service ──profile.created|updated|privacy-changed|deleted.v1──► RabbitMQ ──► projeção `usuario` no MySQL legado
                                                                     └──► social_graph_service (cópia local dos perfis)
social_graph_service ──follow.*.v1──► RabbitMQ ──► projeção `relacao` no MySQL legado
```

A **conta** (e-mail, senha, sessão) é do `authentication_service`; o **perfil** (username, nome, foto, privacidade) é do
`profile_service` e tem o mesmo UUID da conta. O PostgreSQL é uma instância com **um banco por serviço**. A tabela
`usuario` do MySQL é só uma projeção do perfil para os JOINs do legado (some quando os demais domínios migrarem).

O backend legado **não** valida mais token: só aceita `X-User-Id` vindo do gateway e não pode ficar acessível
diretamente da internet (por isso o `compose` não publica a porta dele).

Veja `authentication_service/README.md`, `profile_service/README.md`, `docs/plano-dominio-identidade-servico-proprio.md` e `docs/plano-dominio-perfil-servico-proprio.md`.

## Aviso: esquema do MySQL

`infra/mysql/schema.sql` é a fonte do esquema do legado em desenvolvimento (as tabelas além de `usuario` foram **inferidas do código**
do backend Javalin). Um volume MySQL criado antes do `profile_service` tem o esquema antigo de `usuario`: como ainda não há dados
reais, recrie-o (`docker compose rm -sf mysql && docker volume rm social-flux_mysql_data`).
