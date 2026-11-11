# profile_service

Serviço de **perfil de usuário** do Social Flux: perfil, busca, foto de perfil e privacidade da conta.
Spring Boot 4 · Java 21 · PostgreSQL (banco próprio) · Flyway · RabbitMQ (outbox) · resource server JWT RS256.

Plano e decisões: [`../docs/plano-dominio-perfil-servico-proprio.md`](../docs/plano-dominio-perfil-servico-proprio.md).

## Como se relaciona com os outros serviços

```
Navegador ─► Caddy ──► /api/usuarios*, PATCH /api/img/usuarios/*, /media/avatars/*  ─► profile_service
                       (cookie -> Bearer; forward_auth no auth confere a revogação)
authentication_service ──PUT /internal/profiles/{uuid} (token de serviço)──► profile_service   (cadastro)
authentication_service ──account.registered.v1 / account.deleted.v1 (RabbitMQ)──► profile_service
profile_service ──profile.created|updated|privacy-changed|deleted.v1 (RabbitMQ)──► projetor MySQL (legado) e futuros serviços
```

- **Identidade:** o perfil tem o **mesmo UUID da conta** (`sub` do JWT). O serviço **nunca** usa um id vindo do cliente
  como autoridade: a rota `PATCH /api/usuarios/{id}` só aceita o `id` do próprio usuário autenticado (inteiro legado ou UUID)
  e ignora o `id` do corpo.
- **Quem vê o quê (D3/D9):** qualquer usuário autenticado vê a visão **pública** (`id`, `accountId`, `username`, `nome`,
  `biografia`, `foto_perfil`, `foto_url`, `privado`) — inclusive de contas privadas; **telefone, sexo e data de nascimento só o
  dono** vê (`GET /api/usuarios/me` e a própria visão em `GET /api/usuarios`). E-mail pertence ao authentication_service.
- **Id inteiro (`id`):** temporário, só para os domínios ainda no backend legado (MySQL). `legacy_id` vem de uma sequence.

## API pública (mesmo contrato que o frontend usava)

| Rota | Descrição |
| --- | --- |
| `GET /api/usuarios?username=` / `?id=` | Perfil (público; do dono inclui dados pessoais). `id` aceita o inteiro legado ou o UUID. 404 uniforme |
| `GET /api/usuarios/me` | Perfil do usuário logado |
| `GET /api/usuarios/search?search=` | 2–50 caracteres, curingas do `LIKE` escapados, ordenado por relevância, máx. 10, 60/min por usuário |
| `PATCH /api/usuarios/{id}` | `username`, `nome`, `biografia`, `privado` (todos opcionais). Username é livre (D7), 3–30 chars `a-z0-9._` |
| `PATCH /api/img/usuarios/{id}` | multipart `foto_perfil` (PNG/JPEG, ≤ 5 MB, ≤ 16 MP); `foto_antiga` é ignorado |
| `GET /media/avatars/{chave}` | Avatar (D8: público), JPEG 256×256 re-codificado, `nosniff`, cache imutável |

Erros: `{ "mensagem", "sucesso": false, "codigo" }`.

## API interna (`/internal/**`, token de serviço, nunca roteada pelo gateway)

| Rota | Escopo | Descrição |
| --- | --- | --- |
| `PUT /internal/profiles/{uuid}` | `profiles:write` | Criação idempotente no cadastro (o auth chama). Devolve `legacyId` |
| `DELETE /internal/profiles/{uuid}` | `profiles:write` | Compensação: só perfis ainda `PENDING` |
| `GET /internal/profiles/{uuid}`, `POST /internal/profiles/batch-get` | `profiles:read` | Visão pública |
| `GET /internal/profiles?cursor=&limit=` | `profiles:read` | **Bootstrap** de novos consumidores (cópia local), paginado |

O token de serviço é um JWT RS256 de 5 min emitido pelo authentication_service (`sub=svc:authentication-service`,
`aud=profile-service`, `scope=...`). Tokens de usuário nunca têm escopos, e tokens de serviço nunca têm papéis.

## Eventos (RabbitMQ, exchange `socialflux.events`, routing key = tipo)

Publica (via **outbox transacional**): `profile.created.v1`, `profile.updated.v1`, `profile.privacy-changed.v1`,
`profile.deleted.v1`. O payload é o estado **público** + `aggregateVersion` (consumidores ignoram versões antigas); **nunca**
carrega telefone, sexo, nascimento ou e-mail. Consome: `account.registered.v1` (ativa o perfil `PENDING → ACTIVE`) e
`account.deleted.v1` (apaga dados pessoais e libera o username). Consumo idempotente (tabela `processed_message`).
Falhas repetidas vão para a fila `socialflux.events.dead`.

**Novo serviço consumidor:** declare a sua fila, ligue ao exchange com `profile.#` e faça o bootstrap por
`GET /internal/profiles` antes de depender dos eventos.

## Projeção no MySQL legado (temporária)

Com `PROFILE_LEGACY_ENABLED=true`, um consumidor (`LegacyProjectionHandler`) mantém a tabela `usuario` do MySQL
(JOINs do backend Javalin) a partir dos eventos `profile.*`, de forma idempotente (`profile_version`). A tabela só tem
colunas de perfil — nenhum dado pessoal. Removida quando Relações, Postagens e Notificações migrarem.

## Rodando

Na raiz do repositório (usa o `.env` e o mesmo PostgreSQL do auth, em um **banco próprio** `profile`):

```bash
docker compose up -d                  # postgres, mysql, rabbitmq, mailpit (+ cria o banco `profile`)
powershell -ExecutionPolicy Bypass -File scripts/dev/run-auth.ps1       # 8081   (ou ./scripts/dev/run-auth.sh)
powershell -ExecutionPolicy Bypass -File scripts/dev/run-profile.ps1    # 8082   (ou ./scripts/dev/run-profile.sh)
```

Testes (PostgreSQL real embutido, JWKS local e JWT RS256 de verdade; sem Docker):

```bash
mvn test
```

## Configuração (variáveis de ambiente)

| Variável | Descrição |
| --- | --- |
| `PROFILE_DB_URL` / `PROFILE_DB_NAME` | Banco do serviço (padrão `jdbc:postgresql://localhost:5432/profile`) |
| `PROFILE_DB_USER` / `PROFILE_DB_PASSWORD` | Se ausentes, **reutilizam** `IDENTITY_DB_USER` / `IDENTITY_DB_PASSWORD` do `.env` |
| `AUTH_JWKS_URI` | JWKS do authentication_service (só a chave **pública**) |
| `JWT_ISSUER`, `JWT_AUDIENCE`, `PROFILE_SERVICE_AUDIENCE` | Validação do token (usuário × serviço) |
| `PROFILE_STORAGE_DIR` | Diretório dos avatares (padrão `./data/avatars`; no container, o volume `/data/avatars`) |
| `PROFILE_AVATAR_MAX_BYTES`, `PROFILE_AVATAR_MAX_PIXELS` | Limites do upload (5 MB, 16 MP) |
| `RABBITMQ_HOST/PORT/USER/PASSWORD`, `EVENTS_EXCHANGE`, `PROFILE_EVENTS_ENABLED` | Broker e eventos |
| `PROFILE_LEGACY_ENABLED`, `LEGACY_DB_URL/USER/PASSWORD` | Projeção no MySQL legado |
| `PROFILE_ALLOWED_ORIGINS` | Origens aceitas em métodos não seguros (vazio = a própria origem) |

## Limites conhecidos

- A **revogação imediata** (logout geral, troca de senha) é aplicada pelo gateway (`forward_auth`); este serviço só valida
  assinatura/validade do JWT (até 2 h). Não o exponha sem o gateway.
- Rate limit em memória (1 instância). Avatares em disco local (a interface `StorageService` permite trocar por S3/MinIO).
- Um perfil `PENDING` órfão (falha do auth *e* da compensação) mantém o username ocupado até limpeza manual.
