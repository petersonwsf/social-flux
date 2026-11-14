/** Modelos retornados/enviados pela API (espelham as entidades do backend). */

/** LocalDateTime serializado pelo Jackson: [ano, mês, dia, hora, minuto, segundo?] */
export type LocalDateTimeArray = number[]

export type Sexo = 'M' | 'F'

/** Resposta padrão de sucesso/erro do backend. */
export interface Mensagem {
    mensagem: string
    sucesso: boolean
}

/**
 * Perfil devolvido pelo profile_service. `id` é o id inteiro (compatível com os domínios que ainda estão no backend
 * legado); `accountId` é o UUID da conta. Dados pessoais (telefone, sexo, data_nascimento) só vêm no perfil do PRÓPRIO usuário.
 */
export interface Usuario {
    id: number
    accountId?: string
    username: string
    nome: string
    biografia: string | null
    foto_perfil: string | null
    /** Caminho público e imutável da foto (ex.: /media/avatars/av_….jpg). */
    foto_url?: string | null
    privado: boolean
    telefone?: string
    sexo?: Sexo
    data_nascimento?: string
    data_criacao?: string
}

/** Identidade da conta devolvida pelo authentication_service (o perfil vem de GET /api/usuarios/me). */
export interface AuthAccount {
    accountId: string
    email: string
    emailVerified: boolean
}

/** Resposta de /auth/login, /auth/register, /auth/me e /auth/change-password. */
export interface AuthResponse {
    account: AuthAccount
    /** ISO-8601: quando o token de acesso expira. */
    expiresAt: string
    /** ISO-8601: a partir daqui, a próxima ação do usuário renova a sessão. */
    renewAt: string
}

/** Resposta de /auth/refresh. */
export interface RefreshResponse {
    expiresAt: string
    renewAt: string
}

export interface Postagem {
    id: number
    imagem: string | null
    legenda: string | null
    data_postagem: LocalDateTimeArray
    usuario: Usuario
    postagem: Postagem | null
}

export interface Curtida {
    id: number
    id_usuario: number
    id_conteudo: number
}

/** Usuário como aparece nas listas de relações (social_graph_service): perfil público resumido, sem biografia. */
export type UsuarioResumo = Pick<Usuario, 'id' | 'accountId' | 'username' | 'nome' | 'foto_perfil' | 'foto_url' | 'privado'>

export type EstadoRelacao = 'NENHUM' | 'SOLICITADO' | 'SEGUINDO'

/** Relação do usuário logado com outro usuário. */
export interface RelacaoComUsuario {
    estado: EstadoRelacao
    /** O outro usuário segue o logado. */
    segueVoce: boolean
    /** O outro usuário pediu para seguir o logado (conta privada). */
    solicitouVoce: boolean
}

export interface ContagensRelacao {
    seguidores: number
    seguidos: number
}

/** Item de lista paginada de relações; `desde` é a data ISO-8601 da relação ou da solicitação. */
export interface ItemRelacao {
    usuario: UsuarioResumo
    desde: string
}

/** Página de uma lista de relações; `proximoCursor` é `null` na última página. */
export interface PaginaRelacoes {
    itens: ItemRelacao[]
    proximoCursor: string | null
}

/** Solicitações de seguir recebidas; `total` é o número real de pendentes, não só os itens desta página. */
export interface PaginaSolicitacoes extends PaginaRelacoes {
    total: number
}

/** Seguidores em comum; `total` é o número real, mesmo quando `itens` vem limitado. */
export interface EmComum {
    itens: UsuarioResumo[]
    total: number
}

export type NotificationCategory = 'LIKE' | 'COMENT' | 'FOLLOW'

export interface Notification {
    id: number
    sender: Usuario
    receiver: Usuario
    title: string
    description: string | null
    category: NotificationCategory
    id_post: number | null
    viewed: boolean
}

/** O backend espera sender/receiver como JSON serializado em string. */
export interface NotificationPayload {
    sender: string
    receiver: string
    title: string
    description: string | null
    category: NotificationCategory
    id_post: number | null
}

export interface LoginPayload {
    email: string
    senha: string
}

export interface RegisterPayload {
    email: string
    telefone: string
    sexo: Sexo | ''
    data_nascimento: string
    nome: string
    username: string
    senha: string
    confirmarSenha: string
}

export interface ChangePasswordPayload {
    senhaAtual: string
    novaSenha: string
}

export interface EditarPerfilPayload {
    id: number
    username: string
    nome: string
    biografia: string
    privado: boolean
}

export interface CriarPostagemPayload {
    legenda: string
    imagem: File | null
}
