# Backend Social Flux — Análise, falhas e plano de migração para Spring Boot

Data da análise: 2026-11-10 · Escopo: `BackEnd/` (Javalin 6.2 + JDBC puro + MySQL, ~3.000 linhas, 40 classes Java)
Método: leitura integral do código-fonte, do `pom.xml` e do histórico do Git. **Nada foi executado contra um banco real** e o
esquema do MySQL não está no repositório; itens marcados *(a confirmar)* dependem disso.

---

## 1. Resumo executivo

1. **Migrar para Spring Boot é viável e recomendado.** O código é pequeno, sem testes e sem camadas de segurança reutilizáveis;
   quase todos os problemas encontrados são resolvidos "de graça" por recursos do Spring (injeção de dependência, Spring
   Security, validação, transações, pool de conexões, tratamento de erro central). Custo estimado: **~3 semanas de trabalho
   focado** (ver §6), com o contrato HTTP atual preservado para o frontend não quebrar.
2. **Javalin não é a causa dos problemas.** As falhas vêm de decisões de arquitetura (segurança opt-in por handler, conexão
   única compartilhada, IDs vindos do cliente). Trocar de framework sem corrigir isso apenas moveria os erros de lugar —
   por isso o plano trata correção e migração juntos.
3. **Há vulnerabilidades críticas já em produção** (§3). As três mais urgentes, que **não devem esperar a migração**:
   - o `jwt.secret` está **commitado e publicado em `origin/main`** (commit `d653009`) — qualquer pessoa com acesso ao
     repositório consegue forjar um token de qualquer usuário;
   - **autorização inexistente (IDOR)**: o servidor confia em IDs enviados pelo cliente, então qualquer usuário logado altera
     perfil/foto de outros, aceita/remove solicitações alheias, lê notificações alheias e se passa por outro usuário ao
     seguir/curtir/notificar;
   - **exclusão arbitrária de arquivos** pelo parâmetro `foto_antiga` (path traversal) e possível leitura de arquivos no
     endpoint de imagens.
4. Há também **bugs de lógica** que quebram funcionalidades para usuários reais (ex.: comparação de `Integer` com `!=` impede
   usuários com `id ≥ 128` de editar o perfil sem trocar o username e de excluir as próprias postagens).

> **Correção de uma afirmação anterior.** Em uma resposta anterior eu disse que "comentário só com texto parece falhar
> (`PostagemController`, linha 45)". Isso estava **errado**: o segundo ramo (linha 63) trata texto sem imagem. O problema real é
> o inverso: **uma postagem só com imagem (sem legenda) é recusada**, apesar de a mensagem de erro dizer "coloque uma imagem,
> ou um texto" (ver LOG-08).

---

## 2. Visão geral da arquitetura atual

```
HTTP ─► Javalin (Jetty 11) ─► Controller (static, AuthValidate manual) ─► Service ─► DAO (JDBC) ─► MySQL
                                                  │
                          App.connection (UMA java.sql.Connection compartilhada por todas as threads)
```

| Aspecto | Situação atual |
| --- | --- |
| Injeção de dependência | Nenhuma: serviços criados em `App` e buscados por `ctx.appData(Keys.X)` |
| Segurança | `AuthMiddleware.AuthValidate(ctx)` chamado **manualmente** em cada handler (opt-in) |
| Banco | `HikariCP` configurado, mas **só 1 conexão é retirada e compartilhada** (`App.java:64,86`) |
| Persistência | JDBC cru, `ResultSet` devolvido do DAO e consumido na camada de serviço |
| Modelo | Entidades serializadas direto para JSON (`Usuario` tem `senha`; remoção manual com `setSenha(null)`) |
| Erros | `try/catch` por controller, respostas inconsistentes (200 vazio, 500 sem corpo, `Mensagem`) |
| Arquivos | Gravados em `src/main/resources/public/images/` **relativo ao diretório de execução** |
| Config | `application.properties` **versionado**, inclusive com segredo |
| Testes / CI / Docker | Nenhum / nenhum / nenhum no backend |
| Esquema SQL | Não está no repositório (sem migrations) |
| Dependências sobrando | `mongodb-driver-sync`, `javalin-rendering`, JUnit/Mockito sem uso; `mysql-connector-java` (artefato descontinuado) e um `.jar` do conector versionado em `lib/` |

---

## 3. Falhas de segurança

Severidade: **Crítica** (explorável agora, impacto alto) · **Alta** · **Média** · **Baixa**.

### SEC-01 — Segredo JWT e credenciais versionados · Crítica
- `BackEnd/src/main/resources/application.properties` está **rastreado** pelo Git (apesar de `src/main/resources/` estar no
  `.gitignore`) e aparece em 4 commits. O commit `d653009` contém `jwt.secret` (32 caracteres) e já está em `origin/main`.
- Impacto: com o segredo, qualquer pessoa gera um JWT válido com `sub` = JSON de **qualquer usuário** e passa como ele.
  Credenciais do banco também aparecem no histórico (e o usuário do banco aparece em `logs/app.log`, que também é versionado).
- Não consegui verificar se o repositório no GitHub é público (`gh` não instalado). **Trate como vazado.**
- Correção: rotacionar `jwt.secret` e a senha do MySQL **agora**; mover segredos para variáveis de ambiente/secret manager;
  `git rm --cached` nos arquivos; limpar o histórico (`git filter-repo`) *depois* de rotacionar; adicionar *secret scanning*.

### SEC-02 — Autorização ausente (IDOR) em quase todas as rotas · Crítica
O servidor autentica (token válido), mas **nunca verifica se o recurso pertence a quem chamou**. Exemplos confirmados no código:

| Rota | Problema |
| --- | --- |
| `PATCH /api/usuarios/{id}` (`UsuarioController:108`) | Usa o `id` **do corpo** para o `UPDATE` (`UsuarioDao:90`); o `{id}` da URL só serve para checar username. Qualquer logado edita nome/bio/username/privacidade de outro |
| `PATCH /api/img/usuarios/{id}` (`:65`) | Troca/apaga a foto de qualquer `id` |
| `POST /api/usuarios/{id}` (`:144`) | Exclui conta de qualquer `id` se souber a senha dele; resposta distingue "senha incorreta"/"não encontrado" e **não há limite de tentativas** (oráculo de força bruta) |
| `POST /api/relacao` (`RelacaoController:24`) | Seguidor vem do **corpo**: qualquer um faz qualquer usuário seguir qualquer outro |
| `PATCH/DELETE /api/relacao/{id}` (`:93,105`) | Qualquer logado aceita/recusa/remove qualquer solicitação por `id` |
| `POST /api/curtida` / `DELETE /api/curtida/{id}` | `id_usuario` do corpo; qualquer um curte em nome de outro e remove curtida alheia |
| `POST /api/notification` (`NotificationController:24`) | `sender`/`receiver` vêm do corpo: **notificações forjadas** (spam/assédio) |
| `GET /api/notification?userId=` e `PATCH …/{id_receiver}` | Lê e marca como vistas as notificações de qualquer usuário |
| `GET /api/postagem?id_seguidor=`, `GET /api/relacao?...`, `GET /api/relacao/compartilhado` | Exfiltra o feed e o grafo social de qualquer usuário |

Correção estrutural: **nunca aceitar identidade do cliente**; derivar o usuário do token (`Authentication`/`Principal`) e
verificar propriedade na camada de serviço.

### SEC-03 — Privacidade de conta privada só existe no frontend · Alta
O campo `privado` é respeitado apenas na UI. `GET /api/postagem?id_user=…`, `…&allPosts=True`, `…&mediaPosts=True`,
`/api/relacao?id_seguido=…` retornam tudo de contas privadas para qualquer usuário logado. Pior: ao criar relação,
`aceito = !seguido.getPrivado()` (`RelacaoController:41`) usa o `privado` **enviado pelo cliente** — basta mandar
`privado:false` para ser aceito automaticamente em conta privada.

### SEC-04 — Path traversal no sistema de arquivos · Crítica (exclusão) / Alta (leitura, *a confirmar*)
- **Exclusão arbitrária (confirmado):** `atualizarFoto` recebe `foto_antiga` do formulário (`UsuarioController:70,80`) e chama
  `ImagemUtil.removerImagem(id, foto_antiga)` → `new File("…/images/"+id+"/"+imageName).delete()` (`ImagemUtil:80`). Com
  `foto_antiga=../../../outro-arquivo` o servidor apaga arquivos fora da pasta (permissão do processo Java).
- **Leitura arbitrária (a confirmar):** `ImagemController:24` monta o caminho com `id_usuario` e `id_imagem` da URL sem
  normalizar, **sem autenticação**, e devolve o conteúdo com `Content-Type: image/<extensão>`. Depende de como o Jetty 11
  trata `%2e%2e`, `%2F` e `%5C` no segmento; deve ser tratado como explorável até provar o contrário (o `application.properties`
  contém segredos).
- Correção: nomes de arquivo gerados pelo servidor (UUID) e **o cliente nunca informa nome/caminho**; resolver contra um
  diretório base com `Path.normalize()` + `startsWith(base)`; armazenar fora do classpath/árvore de código.

### SEC-05 — Upload de arquivos sem defesa em profundidade · Alta
- Tipo validado só por extensão e `Content-Type` **informados pelo cliente** (`ImagemUtil:20-21,47-60`); o arquivo de post é copiado
  cru (`Files.copy`) sem decodificar a imagem (qualquer conteúdo com extensão `.png`).
- Sem limite de tamanho/dimensões configurado → *image bombs* e DoS de memória em `ImageIO.read` + `getScaledInstance`.
- Imagens servidas **sem autenticação** e sem `X-Content-Type-Options: nosniff`.
- Correção: validar *magic bytes*, decodificar e **re-codificar** (remove metadados/EXIF e payloads), limitar tamanho e pixels,
  gerar nome no servidor, servir com tipo fixo e `nosniff`.

### SEC-06 — Autenticação · Média/Alta
- Sem limitação de tentativas (força bruta no `/login`, `/register` e na confirmação de senha da exclusão).
- Enumeração de usuários: 404 "Usuário não foi encontrado" vs 401 "Senha incorreta" (`AuthController:61-64`).
- Política de senha, idade mínima (18 anos) e formato de telefone são validados **só no frontend**; o backend só checa "não vazio".
- JWT guarda o **JSON inteiro do usuário** em `sub` (e-mail, telefone, data de nascimento) e fica desatualizado até o
  reemissão; 48 h de validade, sem refresh e **sem revogação** (excluir conta/trocar senha não invalida o token).
- `BCrypt` via `jbcrypt 0.4` (biblioteca sem manutenção desde 2010; os hashes `$2a$` continuam compatíveis com o Spring Security).

### SEC-07 — Transporte e CORS · Alta
A API roda em `http://165.227.76.216` (sem TLS): senha e cookie trafegam em texto claro e o cookie não pode usar `Secure`. A
origem CORS está fixa no código (agora configurável via `cors.allowed-origin`). Falta: HTTPS (Caddy já existe no servidor),
cabeçalhos de segurança (HSTS, `nosniff`, `Referrer-Policy`) e política CSRF explícita (hoje: cookie `SameSite=Lax`).

### SEC-08 — Exposição de dados e logs · Média
- Entidades são serializadas diretamente; a proteção do hash depende de lembrar de chamar `setSenha(null)` em cada ponto.
- `logs/app.log` está no Git, com caminhos locais e nome de usuário do banco; `logger.error("…" + e)` descarta o *stack trace*.
- Erros 500 sem corpo/inconsistentes dificultam monitoramento e podem vazar mensagens internas (`e.getMessage()` em 400/500).

### SEC-09 — Dependências e build · Média *(a confirmar com scanner)*
`mysql-connector-java 8.0.33` (artefato descontinuado, anterior a correções de CVE conhecidas do Connector/J), Jetty 11 (fim de
suporte comunitário), `jackson-databind 2.17.0` + `jsr310 2.15.0` (versões divergentes), `mongodb-driver-sync` sem uso,
`jar` do conector versionado em `lib/`. Rodar **OWASP Dependency-Check**/`mvn versions:display-dependency-updates`.

### SEC-10 — Segurança opt-in por handler · Alta (causa-raiz)
`ImagemController` esquece o `AuthValidate` (não há chamada). Qualquer rota nova esquecida fica pública. Em Spring Security o
padrão é **negar tudo** e liberar explicitamente.

---

## 4. Falhas de lógica e confiabilidade

| ID | Sev. | Onde | Descrição / efeito |
| --- | --- | --- | --- |
| LOG-01 | Crítica | `App.java:64,86` | **Uma única `Connection` JDBC** é compartilhada por todas as threads do Jetty e nunca devolvida ao Hikari. Não é thread-safe para uso concorrente; após o `wait_timeout` do MySQL (8 h ocioso) a conexão morre e **todas as requisições falham até reiniciar**. O pool de 10 nunca é usado |
| LOG-02 | Alta | DAOs | `PreparedStatement`s de consulta nunca são fechados (vazamento de cursores no servidor); `ResultSet` atravessa camadas |
| LOG-03 | Alta | `PostagemController:171`, `UsuarioController:116`, `NotificationService:27` | **`Integer != Integer` compara referência**: falso-positivo para `id ≥ 128` (cache de `Integer`). Efeitos: o usuário com id ≥ 128 não consegue editar o perfil mantendo o username (recebe 409 "já em uso") nem excluir **as próprias** postagens (422), e a notificação para si mesmo não é suprimida |
| LOG-04 | Alta | `UsuarioController:108-127` | Atualiza pelo `id` do corpo (ver SEC-02); `privado == null` gera NPE (500); username não passa pela regex do cadastro (`^[a-z0-9._]+$`) nem por checagem de vazio |
| LOG-05 | Média | `PostagemController:174-176` | Excluir postagem: se o arquivo da imagem não existir, `removerImagem` lança exceção e **a postagem nunca é excluída** (500). Postagem inexistente gera NPE (500, não 404). Imagens das respostas não são removidas; comportamento com respostas dependentes (FK) *(a confirmar)* |
| LOG-06 | Média | `CurtidaController`, `RelacaoController` | "Não encontrado" responde **200 com corpo vazio** (o frontend precisa tratar `""`); `DELETE`/`PATCH` em id inexistente retornam 200 silenciosamente; remover curtida devolve `sucesso:false` em caso de sucesso; ramos placeholder `"Oloco"` / `"Sei lá"` em `CurtidaController:53,55` |
| LOG-07 | Média | `RelacaoController:32-50` | Permite **seguir a si mesmo**; checagem de duplicidade não é atômica (depende de índice único no banco *(a confirmar)*); relação com usuário inexistente → 500 |
| LOG-08 | Média | `PostagemController:45,63` | Postagem **somente com imagem** é recusada (ambos os ramos exigem `legenda`), contradizendo a mensagem de erro; `id_postagem` não numérico vira 500; post-pai não é validado |
| LOG-09 | Média | `PostagemService.resultSetToPostagem` | **N+1 e recursão**: para cada post, outra consulta busca o pai (e o pai do pai…) enquanto o `ResultSet` externo ainda está aberto. Feeds/listas **sem paginação** |
| LOG-10 | Média | `NotificationController:24-45` | Categoria inválida/`id_post` de tipo errado → exceção não tratada (500); resposta sempre vazia; usa `ctx.body()` sem validação de esquema |
| LOG-11 | Média | `PostagemController:53,67` | `LocalDateTime.now()` no fuso do servidor, e o frontend interpreta o array como **UTC** → horários errados se o servidor não estiver em UTC |
| LOG-12 | Baixa | `UsuarioController:132` | Pesquisa sem `search` não responde; `LIKE` sem escape de `%`/`_` |
| LOG-13 | Média | `UsuarioController:144` | Excluir conta não apaga imagens nem invalida a sessão; depende de `ON DELETE CASCADE` no banco *(a confirmar)* |
| LOG-14 | Média | `App.java` | Rotas registradas **após** `start()`; serviços criados no evento `serverStarted` (requisições iniciais podem encontrar serviços nulos); `System.exit(1)` em lógica de configuração; `app.exception` só define status 500 sem corpo |
| LOG-15 | Baixa | Geral | Mistura de português/inglês, nomes inconsistentes (`RelacaoExiste`, `RelacoesNaoAceitas` em PascalCase, `usernameExistenrte`), duplicação de ~6 consultas quase idênticas em `PostagemDao`/`RelacaoDao` |
| LOG-16 | Média | Repositório | Esquema do banco ausente, sem migrations, sem testes, sem CI, sem Dockerfile — o ambiente não é reproduzível |

---

## 5. Avaliação: vale migrar para Spring Boot?

### 5.1 O que o Spring resolve diretamente

| Problema | Recurso do Spring Boot |
| --- | --- |
| SEC-02/10 (autorização, opt-in) | **Spring Security**: *deny-by-default*, `Authentication` injetado, `@PreAuthorize`/regras por serviço |
| SEC-01 (segredos) | `application.yml` + variáveis de ambiente, perfis `dev`/`prod`, `.env` fora do Git |
| SEC-04/05 (arquivos) | `Path` + abstração `StorageService`, `spring.servlet.multipart.max-*`, `ResourceHttpRequestHandler` |
| SEC-06 (força bruta) | Bucket4j/`resilience4j` ou limite no Caddy; `AuthenticationFailureHandler` |
| SEC-08 (erros) | `@RestControllerAdvice` + `ProblemDetail`, DTOs (records) em vez de entidades |
| LOG-01/02 (conexão) | `DataSource` Hikari gerenciado pelo Spring, `JdbcTemplate`/JPA fecham recursos, **transações** `@Transactional` |
| LOG-03/04/07/10 (validação) | Bean Validation (`@Valid`, `@NotBlank`, `@Pattern`, `@Past`), `equals` correto com `Objects.equals` |
| LOG-09 (N+1, paginação) | Spring Data (`Pageable`, `@EntityGraph`/`JOIN FETCH`), consultas por projeção |
| LOG-16 (reprodutibilidade) | Flyway/Liquibase, Testcontainers (MySQL real nos testes), Actuator (`/health`), imagem Docker em camadas |
| Documentação | `springdoc-openapi` gera Swagger do contrato |

### 5.2 Custos e riscos
- **Curva de aprendizado** (DI, auto-configuração, Spring Security) e "mágica" que dificulta depuração no início.
- **Consumo maior** que Javalin: tipicamente centenas de MB de RAM e arranque de segundos (relevante na VPS atual; medir).
- **Risco de regressão** no frontend recém-migrado: mitigado mantendo **o mesmo contrato HTTP** na primeira entrega.
- Reescrita de ~3 mil linhas: pequena; **sem testes** hoje, então é preciso criar testes de contrato *antes* de trocar para
  comparar comportamento.

### 5.3 Alternativas consideradas
| Opção | Prós | Contras |
| --- | --- | --- |
| **A. Spring Boot (recomendada)** | Segurança, dados, validação, testes, ecossistema e mercado | Mais pesado; curva inicial |
| B. Manter Javalin e corrigir | Menor mudança, leve | Você reimplementa à mão segurança, validação, transações, pool, testes; o mesmo tipo de falha tende a reaparecer |
| C. Quarkus/Micronaut | Leves e rápidos | Menos material/conhecimento disponível; mesmo esforço de reescrita que o Spring |

**Recomendação: A**, com a escolha da versão em https://start.spring.io no início do projeto (usar a **linha estável mais
recente**; a 3.5.x já passou do período de suporte OSS publicado — confirmar a vigente), Java 21 (LTS; o código atual compila
para 17 e roda em 22).

### 5.4 Stack-alvo proposta
- **Web**: Spring MVC (servlet) · **Segurança**: Spring Security (stateless) + JWT em cookie HttpOnly, resolvido por filtro/`BearerTokenResolver` (jjwt ≥ 0.12 ou Nimbus via `oauth2-resource-server`)
- **Dados**: Spring Data JPA (Hibernate) **ou** Spring Data JDBC/`JdbcTemplate` (mais próximo do SQL atual e do esquema existente — ver decisão D2) · **Migrations**: Flyway · **MySQL 8**
- **Validação**: Bean Validation · **Mapeamento**: records como DTOs (MapStruct opcional)
- **Observabilidade**: Actuator, logs estruturados (sem PII/segredos) · **Docs**: springdoc-openapi
- **Testes**: JUnit 5, Spring Security Test, **Testcontainers (MySQL)**, REST-assured/MockMvc
- **Senhas**: `BCryptPasswordEncoder` (aceita os hashes atuais `$2a$`) — sem exigir reset de senha
- **Build/Deploy**: Maven wrapper, `Dockerfile` multi-stage (JRE, usuário não-root, jar em camadas), `docker-compose` (app + MySQL), Caddy como proxy HTTPS

---

## 6. Plano de implementação

Princípios: (1) **contenção primeiro**, (2) **testes de contrato antes de trocar**, (3) **mesmo contrato HTTP** na primeira
entrega, (4) migrar por módulos com o frontend sempre funcionando, (5) endurecer a segurança *durante* a migração, não depois.

### Fase 0 — Contenção (1–2 dias, **faça já**, independe de Spring)
1. **Rotacionar** `jwt.secret` e a senha/usuário do MySQL; invalidar sessões (a rotação já derruba todos os tokens).
2. Remover do Git: `application.properties`, `logs/`, `lib/*.jar`, `dependency-reduced-pom.xml`, `.idea/`, imagens
   (`git rm --cached`); criar `application.properties.example`; depois `git filter-repo` e *force-push* coordenado; ativar
   *secret scanning* no GitHub.
3. Hotfix mínimo no Javalin enquanto a migração não chega: usar o `id` do token (não do cliente) em atualizar perfil/foto,
   relação, curtida e notificações; checar `seguido` no `aceitar/remover`; corrigir `!=` para `Objects.equals`; sanitizar
   `foto_antiga`/`id_imagem` (`normalize` + `startsWith`) e exigir autenticação em `/api/img`; limitar tamanho do upload.
4. Colocar a API atrás de **HTTPS** (Caddy) e ligar `cookie.secure=true`.
5. Exportar o esquema atual (`mysqldump --no-data`) e commitar como baseline.

### Fase 1 — Fundações e rede de segurança (3–4 dias)
1. Criar projeto Spring Boot (Maven, Java 21) em `BackEnd/` ou `backend-spring/` (decisão D1) com perfis `dev`/`prod`.
2. **Flyway** `V1__baseline.sql` a partir do dump; adicionar **índices/restrições que o código supõe** e hoje podem faltar:
   `UNIQUE(username)`, `UNIQUE(email)`, `UNIQUE(id_usuario,id_conteudo)` em curtida, `UNIQUE(id_seguidor,id_seguido)` em
   relação, FKs com `ON DELETE CASCADE` onde fizer sentido, `CHECK(id_seguidor <> id_seguido)`.
3. Testcontainers + testes de contrato que **fixam o comportamento atual** das rotas que o frontend usa (inclui os formatos
   `Mensagem{mensagem,sucesso}`, `LocalDateTime` como array, corpo vazio 200 para "sem curtida/relação").
4. CI (GitHub Actions): build, testes, OWASP Dependency-Check, *secret scan*.

### Fase 2 — Segurança e núcleo de usuários (4–5 dias)
1. Spring Security stateless; filtro lê JWT do cookie HttpOnly (**e** do header `Authorization`, para clientes de API);
   `sub` = **id do usuário** (não o JSON inteiro); claims mínimas, `exp` menor (ex.: 2–8 h) e *refresh* opcional (D4).
2. Endpoints `POST /login`, `POST /register`, `POST /logout`, `GET /api/me` **com o contrato já usado pelo frontend**.
3. Regras: validação server-side (senha ≥ 8, e-mail, username `^[a-z0-9._]+$`, idade ≥ 18, telefone), mensagens de login
   **uniformes**, limite de tentativas (Bucket4j) por IP/e-mail.
4. `UsuarioService` com propriedade verificada (`principal.id == id`), DTOs sem `senha`, edição de perfil/foto/exclusão.
5. Headers: HSTS, `nosniff`, `Referrer-Policy`, CSP básica; CORS por propriedade; CSRF: manter `SameSite=Lax` + origem
   restrita, ou ativar token duplo-cookie (D5).

### Fase 3 — Domínio: postagens, relações, curtidas, notificações (5–6 dias)
1. **Notificações geradas no servidor** (ao curtir/comentar/seguir) — remover o `POST /api/notification` público
   (o frontend deixa de chamá-lo; mudança coordenada, ver §7).
2. Autorização por serviço: só o dono exclui postagem; só o seguido aceita/recusa; só o dono remove a própria curtida;
   não seguir a si mesmo; `aceito` decidido pelo servidor a partir do `privado` **do banco**.
3. **Privacidade**: perfis privados só mostram postagens/seguidores a seguidores aceitos (e ao dono).
4. Postagens: aceitar imagem **ou** legenda; validar post-pai; **paginação** (`page/size` ou cursor) em feed/perfil/respostas;
   substituir N+1 por *fetch join*/consulta com pai; limitar profundidade da cadeia de pais.
5. Datas em **UTC** (`Instant`/`LocalDateTime` UTC) com contrato claro com o frontend (ISO-8601 ou o array atual).

### Fase 4 — Armazenamento de imagens (2 dias)
`StorageService` (interface) com implementação em disco configurável (`app.storage.dir`, **fora** do código e do classpath) e
caminho para S3/MinIO depois; validação por *magic bytes*, re-codificação, limites de tamanho/pixels, nomes UUID, exclusão
segura (ex.: ao apagar post/conta); endpoint `/api/img/{userId}/{file}` com normalização de caminho, `Content-Type` fixo e
cache. Migrar as imagens existentes para o novo diretório (script único).

### Fase 5 — Erros, observabilidade e operação (2 dias)
`@RestControllerAdvice` (mantém `{mensagem, sucesso}` e adiciona `status`/`codigo`), logs sem PII, Actuator
(`/actuator/health` protegido), `Dockerfile` multi-stage + `docker-compose` (app + MySQL + volume de imagens), perfil `prod`
com `cookie.secure=true`, variáveis de ambiente documentadas, backup do banco.

### Fase 6 — Cutover e limpeza (2 dias)
1. Subir o Spring em paralelo (porta/host diferentes), rodar os testes de contrato **e** os fluxos do frontend (login,
   feed, comentar, seguir, notificações, perfil, foto, excluir conta, mobile).
2. Trocar o *reverse proxy* do Caddy (rollback = apontar de volta ao Javalin).
3. Remover o Javalin, dependências mortas (Mongo, javalin-rendering) e documentação obsoleta; atualizar README e `.env.example`.

**Total estimado: ~19–22 dias úteis** para uma pessoa (≈ 4 semanas corridas), podendo cair para ~3 semanas se as Fases 3 e 4 forem
paralelizadas. As fases 0 e 1 reduzem o risco imediato mesmo que a migração seja adiada.

### Critérios de aceite
- Nenhum segredo no Git; varredura de segredos e de dependências verdes no CI.
- Nenhuma rota usa identidade vinda do cliente; testes negativos de IDOR para **cada** rota mutável.
- Testes de path traversal/upload (nomes maliciosos, arquivo falso `.png`, imagem gigante) passando.
- Perfil privado inacessível a não seguidores (teste de integração).
- Usuários com `id > 127` editam perfil e excluem postagens (teste de regressão para LOG-03).
- Reinício do MySQL/ociosidade longa **não** derruba a API (teste de resiliência do pool).
- Contrato HTTP idêntico ao atual nas rotas usadas pelo frontend (testes de contrato).

---

## 7. Impacto no frontend (mudanças coordenadas)

| Mudança no backend | Ajuste no frontend |
| --- | --- |
| Remover `POST /api/notification` público | Parar de chamar `notificationService.notificar*`; o servidor cria as notificações |
| "Não encontrado" passa a 404 em vez de 200 vazio (opcional, fase final) | `curtidaService.buscar`/`relacaoService.buscar` tratam 404 como `null` |
| Paginação | Hooks de feed/perfil/respostas ganham "carregar mais" |
| Datas em ISO/UTC (se alterado) | Atualizar `lib/dates.ts` |
| Rate limit (429) | Exibir mensagem amigável (já passa por `getErrorMessage`) |

Manter o contrato atual na primeira entrega elimina a necessidade de mexer no frontend para o cutover.

---

## 8. Decisões que precisam do seu parecer

| # | Decisão | Opções (recomendada em **negrito**) |
| --- | --- | --- |
| D1 | Onde criar o novo projeto | **Novo módulo `backend-spring/` ao lado, trocando no cutover** · reescrever in-place em `BackEnd/` |
| D2 | Acesso a dados | **Spring Data JPA** (produtividade, paginação) · Spring Data JDBC/`JdbcTemplate` (SQL explícito, menos "mágica") |
| D3 | Versão do Spring Boot / Java | **Linha estável mais recente + Java 21** · fixar a 3.5.x (conferir suporte) |
| D4 | Sessão | **Access token curto + refresh em cookie HttpOnly com rotação** · só access token de 48 h como hoje |
| D5 | CSRF | **`SameSite=Lax` + CORS estrito** (suficiente para SPA no mesmo site) · adicionar token duplo-cookie |
| D6 | Armazenamento de imagens | **Disco fora do código, com interface para S3/MinIO depois** · já usar objeto (S3/MinIO) |
| D7 | Notificações | **Geradas no servidor** (quebra o endpoint público) · manter endpoint, mas validado |
| D8 | Histórico do Git | **Reescrever com `git filter-repo` após rotacionar** · apenas rotacionar e deixar o histórico |
| D9 | Esquema do banco | Você me fornece o `mysqldump --no-data` (necessário para a Fase 1) |

---

## 9. Pontos não verificados / limitações
- Esquema real do MySQL (FKs, índices únicos, `ON DELETE`) — vários itens dependem dele.
- Exploração real do path traversal no `GET /api/img` (depende do tratamento de URL no Jetty 11).
- Visibilidade do repositório no GitHub (público/privado) — assuma vazado.
- Servidor de produção (TZ, `wait_timeout`, TLS, versão de Java) não foi inspecionado.
- CVEs de dependências: recomendo confirmar com OWASP Dependency-Check; não listei números de CVE que não pude validar aqui.
