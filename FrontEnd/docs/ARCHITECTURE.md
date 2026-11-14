# Arquitetura — FrontEnd

SPA do Social Flux. React 18 + TypeScript (strict) + Vite 5 · Bootstrap 5.3 (CSS + JS via `data-bs-*`) · Formik + Yup ·
axios · react-router-dom 6 · ionicons (web component `<ion-icon>`) · date-fns. Porta **5173** em desenvolvimento.

Componentes reutilizáveis: [`COMPONENTS.md`](COMPONENTS.md). Como rodar e autenticação: [`../README.md`](../README.md).
Visão do monorepo: [`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md).

## Como funciona

- Fala **só com a própria origem** (`/auth`, `/api`, `/media`): em produção/containers o Caddy roteia; em `npm run dev` o
  proxy do Vite encaminha ao gateway (`http://localhost:8080`). Necessário porque a sessão usa **cookies HttpOnly**.
- O JavaScript nunca vê os tokens: guarda só `expiresAt`/`renewAt` em memória (`lib/session.ts`). A instância única do axios
  (`lib/api.ts`) renova a sessão (`POST /auth/refresh`) a partir de `renewAt` ou após um `401`, uma única vez por vez (single-flight + Web Locks).
- Conta (authentication_service) e perfil (profile_service) são buscados separadamente pelo `AuthProvider`.
- Fluxo de dados: **página → hook → service → `api` (axios)**. Páginas compõem componentes; hooks guardam estado e chamam os services;
  services só fazem a chamada HTTP tipada.
- Layout responsivo: em telas `lg+` uma sidebar fixa; abaixo disso, cabeçalho no topo e barra de navegação fixa no rodapé.

## Rotas (`src/App.tsx`)

| Rota | Página | Acesso |
| --- | --- | --- |
| `/login` | `Login` (alterna entre entrar e cadastrar) | pública |
| `/forgot-password` | `ForgotPassword` | pública |
| `/reset-password?token=` | `ResetPassword` | pública |
| `/verify-email?token=` | `VerifyEmail` | pública |
| `/` | `Home` (feed de quem o usuário segue) | autenticada |
| `/perfil/:username` | `Perfil` | autenticada |
| `/editar` | `EditarPerfil` | autenticada |
| `/criarPost` | `CriarPostagem` | autenticada |
| `/postagem/:id` | `PostPage` (postagem + respostas) | autenticada |
| `/search` | `SearchUser` (busca no mobile) | autenticada |
| `/notifications` | `Notifications` (notificações e solicitações no mobile) | autenticada |
| `/settings` | `Settings` | autenticada |
| `*` | `NotFound` | pública |

As rotas autenticadas ficam dentro de `ProtectedLayout`, que redireciona para `/login` sem sessão.

## Árvore de diretórios

```
FrontEnd/
├── Dockerfile                    build Node 22 (npm ci + vite build, VITE_* como build args) → nginx sem root na porta 8080
├── .dockerignore / .gitignore    exclui node_modules, dist, logs, arquivos locais
├── nginx.conf                    serve o build: cache longo em /assets, fallback de SPA para index.html
├── index.html                    HTML de entrada do Vite (favicon /logo.ico, div#root)
├── package.json / package-lock.json   dependências e scripts (dev, build, typecheck, lint, preview, start)
├── vite.config.ts                plugin React e proxy de /auth, /api e /.well-known para o gateway
├── tsconfig.json                 referências para tsconfig.app.json e tsconfig.node.json
├── tsconfig.app.json             TS do app: strict, noUnusedLocals/Parameters, noUncheckedIndexedAccess
├── tsconfig.node.json            TS dos arquivos de configuração (vite.config.ts)
├── eslint.config.js              ESLint 9 (typescript-eslint, react, react-hooks, react-refresh)
├── .env.example                  VITE_API_URL (vazio = mesma origem)
├── README.md                     desenvolvimento, variáveis, autenticação e Docker
├── docs/                         ARCHITECTURE.md (este) e COMPONENTS.md
├── public/logo.ico               favicon
├── dist/                         (não versionado) saída do build
└── src/
    ├── main.tsx                  ponto de entrada
    ├── App.tsx                   rotas
    ├── assets/img/               imagens estáticas
    ├── components/               componentes reutilizáveis, por área (ver COMPONENTS.md)
    ├── config/                   variáveis de ambiente
    ├── contexts/                 contextos React (auth, tema, toast, notificações)
    ├── hooks/                    hooks de dados e de acesso aos contextos
    ├── lib/                      infraestrutura: axios, sessão, datas, erros, ícones, imagens, modais
    ├── pages/                    telas (uma pasta por rota)
    ├── services/                 chamadas tipadas à API, por domínio
    ├── styles/                   tema e estilos de componentes
    ├── types/                    modelos da API
    ├── ionicons.d.ts             tipagem JSX de <ion-icon>
    └── vite-env.d.ts             tipagem de import.meta.env
```

## Arquivos

### Entrada

| Arquivo | O que representa |
| --- | --- |
| `src/main.tsx` | Importa Bootstrap (CSS + JS), registra os ícones, carrega os estilos e monta `ThemeProvider > BrowserRouter > ToastProvider > AuthProvider > App` |
| `src/App.tsx` | Tabela de rotas; ao trocar de rota remove backdrops de modais órfãos |

### `src/assets/img/`

| Arquivo | Uso |
| --- | --- |
| `logo.png` | Logo da marca (`Brand`) |
| `profile_photo_default.png` | Foto padrão quando o usuário não tem avatar (`Avatar`) |
| `loading.webp` | Animação de carregamento (`Loading`) |
| `posts.png` | Ilustração de "sem conteúdo" (`NoContent`) |
| `cadeado.webp` | Ilustração de conteúdo protegido / perfil privado (`ProtectedContent`) |
| `notfound.png` | Ilustração de não encontrado (`NotFoundMessage`, página `NotFound`) |
| `messi.jpg` | Não é usado em nenhum lugar |

### `src/config/`

| Arquivo | O que representa |
| --- | --- |
| `env.ts` | `API_URL` (de `VITE_API_URL`, sem barra final; vazio = mesma origem) e `API_BASE_URL` (`${API_URL}/api`) |

### `src/contexts/`

Cada contexto tem um arquivo `xxxContext.ts` (tipo + `createContext`) e um `XxxProvider.tsx` (estado e efeitos), separados para o Fast Refresh.

| Arquivo | O que representa |
| --- | --- |
| `authContext.ts` / `AuthProvider.tsx` | Sessão: `user` (perfil), `account` (UUID, e-mail), `emailVerified`, `carregando`, `login`, `register`, `logout`, `applyAuth`, `updateUser`, `refreshUser`. Restaura a sessão pelos cookies ao abrir, apaga o token antigo do `localStorage` e volta ao login quando a sessão não pode ser renovada |
| `themeContext.ts` / `ThemeProvider.tsx` | Tema claro/escuro via `data-bs-theme` do Bootstrap, persistido no `localStorage` (migra a chave antiga do redux-persist) |
| `toastContext.ts` / `ToastProvider.tsx` | Fila de toasts (`success`, `error` e `notify` para `{ mensagem, sucesso }`) renderizada em `.toast-stack` |
| `notificationsContext.ts` / `NotificationsProvider.tsx` | Notificações do usuário logado e contador de não vistas (`zerarContador`, `marcarComoVistas`); montado dentro do `ProtectedLayout` |

### `src/hooks/`

| Arquivo | O que representa |
| --- | --- |
| `useAuth.ts` | `useAuth()` (contexto de auth) e `useCurrentUser()` (usuário garantido em telas protegidas) |
| `useTheme.ts` / `useToast.ts` / `useNotificacoes.ts` | Acesso aos respectivos contextos (erro se usados fora do provider) |
| `useConta.ts` | Ações da conta: editar perfil, trocar foto, alterar senha, sair de todos os dispositivos, reenviar verificação, excluir conta |
| `usePerfil.ts` | Carrega um perfil por username (`naoEncontrado` em 404) |
| `usePesquisaUsuarios.ts` | Busca de usuários com debounce |
| `usePostagens.ts` | `useFeed`, `usePostagensUsuario` (abas posts/interações/mídia) e `useRespostas` |
| `usePostagemActions.ts` | `useCriarPostagem` (postagem ou resposta) e `useExcluirPostagem` |
| `usePostagemInteracoes.ts` | Junta curtidas, número de respostas e o envio de comentário de um card |
| `useCurtidas.ts` | Curtida própria + total de uma postagem; notifica o autor ao curtir |
| `useNumeroRespostas.ts` | Quantidade de respostas, com incremento local após comentar |
| `useRelacao.ts` | Relação entre o logado e o dono do perfil: `estado` (`NENHUM`/`SOLICITADO`/`SEGUINDO`), contadores e as ações seguir, deixar de seguir/cancelar e remover seguidor |
| `useListaRelacoes.ts` | Lista paginada por cursor (seguidores/seguidos): a 1ª página só é buscada quando `ativo` vira `true`; `carregarMais`, `remover` e `erro` (ex.: conta privada) |
| `useModalAberto.ts` | Diz se um modal do Bootstrap já foi aberto (evento `show.bs.modal`), para carregar o conteúdo só sob demanda |
| `useRelacoesEmComum.ts` | Seguidores em comum entre o logado e outro usuário (`itens` + `total`; `limite` opcional) |
| `useRelacoesPendentes.ts` | Solicitações de seguir recebidas (paginadas), com aceitar/recusar, `carregarMais` e `total` (número real de pendentes, vindo do servidor) |

### `src/lib/`

| Arquivo | O que representa |
| --- | --- |
| `api.ts` | Instância única do axios (`withCredentials`) com interceptors de renovação de sessão e repetição após `401`; `setUnauthorizedHandler` |
| `session.ts` | Temporização da sessão em memória (`setSessionTiming`, `shouldRenewSession`) e `refreshSession` (renovação única, com Web Locks entre abas) |
| `errors.ts` | `getErrorMessage` (texto de qualquer erro) e `getErrorStatus` (status HTTP do axios) |
| `dates.ts` | `calcTempPost` (tempo relativo curto) e `formatDate`, a partir do `LocalDateTime` em array do Jackson |
| `imageUrl.ts` | `getUserImageUrl`: avatar `av_…` → `/media/avatars/…`; imagem de postagem → `/api/img/{usuario}/{arquivo}` |
| `icons.ts` | Registra `<ion-icon>` e embute só os ícones usados (adicione aqui cada ícone novo) |
| `bootstrapModal.ts` | `resetBootstrapModals`: remove backdrop/estado de modais que ficaram abertos ao desmontar a tela |

### `src/pages/`

| Arquivo | O que representa |
| --- | --- |
| `Login/Login.tsx` | Alterna `FormSignin`/`FormSignup`; redireciona para `/` se já logado |
| `ForgotPassword/ForgotPassword.tsx` | Pede o e-mail para redefinição (resposta sempre genérica) |
| `ResetPassword/ResetPassword.tsx` | Define a nova senha com o `token` da URL |
| `VerifyEmail/VerifyEmail.tsx` | Confirma o e-mail com o `token` da URL (chamada única mesmo no StrictMode) |
| `Home/Home.tsx` | Feed das pessoas seguidas |
| `Perfil/Perfil.tsx` | Perfil: informações, seguir, seguidores/seguidos (modais paginados, carregados ao abrir; o dono pode remover seguidores), abas de postagens; conteúdo protegido em conta privada não seguida |
| `Perfil/PerfilPostagens.tsx` | Lista as postagens do perfil conforme a aba (posts, interações, mídia) |
| `EditarPerfil/EditarPerfil.tsx` | Edição de dados do perfil e troca da foto |
| `CriarPostagem/CriarPostagem.tsx` | Nova postagem (texto e/ou imagem) |
| `PostPage/PostPage.tsx` | Postagem completa, contexto da postagem pai e respostas |
| `SearchUser/SearchUser.tsx` | Busca de usuários (no desktop a busca fica na sidebar) |
| `Notifications/Notifications.tsx` | Abas de notificações e solicitações; zera o badge ao abrir e marca como vistas ao sair |
| `Settings/Settings.tsx` | Tema, alterar senha, sair, sair de todos os dispositivos, reenviar verificação e excluir conta |
| `NotFound/NotFound.tsx` | Página 404 |

### `src/services/`

Uma função por chamada, sempre pela instância `api`. Os nomes de rota estão no `README.md` de cada serviço.

| Arquivo | O que representa |
| --- | --- |
| `authService.ts` | authentication_service (`/auth/*`): login, register, me, logout, logoutAll, verifyEmail, resendVerification, forgotPassword, resetPassword, changePassword, deleteAccount (a renovação fica em `lib/session.ts`) |
| `usuarioService.ts` | profile_service: meu perfil, perfil por username, pesquisa, editar perfil, trocar foto |
| `postagemService.ts` | Legado: criar (multipart, com pai opcional), remover, buscar por id, respostas, feed e postagens do usuário por filtro |
| `curtidaService.ts` | Legado: criar, buscar, remover e contar curtidas |
| `relacaoService.ts` | social_graph_service (`/api/relacoes`): estado, contagens, seguir, deixar de seguir/cancelar, remover seguidor, seguidores/seguidos paginados, em comum, solicitações recebidas, aceitar e recusar. Só informa o ALVO; quem age vem do token |
| `notificationService.ts` | Legado: listar, contar não vistas, marcar como vistas e criar notificações de curtida, comentário e novo seguidor (o frontend ainda as cria, ao seguir e ao aceitar uma solicitação) |

### `src/styles/`

| Arquivo | O que representa |
| --- | --- |
| `theme.css` | Paleta e tokens (variáveis CSS) para os temas claro e escuro, sobre o Bootstrap |
| `components.css` | Estilos próprios: sidebar, barras mobile, cards de postagem, avatar, toasts etc. |

### `src/types/` e declarações

| Arquivo | O que representa |
| --- | --- |
| `types/api.ts` | Modelos e payloads da API (`Usuario`, `UsuarioResumo`, `Postagem`, `Curtida`, `EstadoRelacao`, `RelacaoComUsuario`, `ContagensRelacao`, `ItemRelacao`, `PaginaRelacoes`, `PaginaSolicitacoes`, `EmComum`, `Notification`, `Mensagem`, respostas de auth...) |
| `ionicons.d.ts` | Declara `<ion-icon>` no JSX |
| `vite-env.d.ts` | Referência aos tipos do Vite e tipagem de `import.meta.env` (`VITE_API_URL`) |
