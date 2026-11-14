import { api } from '../lib/api'
import type {
    ContagensRelacao,
    EmComum,
    EstadoRelacao,
    PaginaRelacoes,
    RelacaoComUsuario,
} from '../types/api'

/**
 * social_graph_service (`/api/relacoes`). O usuário logado vem sempre do token: aqui só se informa o ALVO da ação.
 * `idUsuario` é o id inteiro do perfil (a API também aceita o UUID da conta).
 */

export async function buscarEstado(idUsuario: number): Promise<RelacaoComUsuario> {
    const { data } = await api.get<RelacaoComUsuario>(`/api/relacoes/${idUsuario}/estado`)
    return data
}

export async function buscarContagens(idUsuario: number): Promise<ContagensRelacao> {
    const { data } = await api.get<ContagensRelacao>(`/api/relacoes/${idUsuario}/contagens`)
    return data
}

/** Conta pública: passa a seguir na hora (`SEGUINDO`). Conta privada: cria a solicitação (`SOLICITADO`). */
export async function seguir(idUsuario: number): Promise<EstadoRelacao> {
    const { data } = await api.put<{ estado: EstadoRelacao }>(`/api/relacoes/seguindo/${idUsuario}`)
    return data.estado
}

/** Deixa de seguir OU cancela a solicitação pendente. */
export async function deixarDeSeguir(idUsuario: number): Promise<void> {
    await api.delete(`/api/relacoes/seguindo/${idUsuario}`)
}

/** Tira um seguidor da lista do usuário logado. */
export async function removerSeguidor(idUsuario: number): Promise<void> {
    await api.delete(`/api/relacoes/seguidores/${idUsuario}`)
}

export async function listarSeguidores(idUsuario: number, cursor?: string): Promise<PaginaRelacoes> {
    const { data } = await api.get<PaginaRelacoes>(`/api/relacoes/${idUsuario}/seguidores`, { params: { cursor } })
    return data
}

export async function listarSeguidos(idUsuario: number, cursor?: string): Promise<PaginaRelacoes> {
    const { data } = await api.get<PaginaRelacoes>(`/api/relacoes/${idUsuario}/seguidos`, { params: { cursor } })
    return data
}

/** Quem o usuário logado segue e também segue `idUsuario`. `limite` (1–100) restringe os itens; `total` é sempre o real. */
export async function buscarEmComum(idUsuario: number, limite?: number): Promise<EmComum> {
    const { data } = await api.get<EmComum>(`/api/relacoes/${idUsuario}/em-comum`, { params: { limite } })
    return data
}

/** Solicitações de seguir RECEBIDAS pelo usuário logado. */
export async function listarSolicitacoes(cursor?: string): Promise<PaginaRelacoes> {
    const { data } = await api.get<PaginaRelacoes>('/api/relacoes/solicitacoes', { params: { cursor } })
    return data
}

export async function aceitarSolicitacao(idSolicitante: number): Promise<void> {
    await api.post(`/api/relacoes/solicitacoes/${idSolicitante}/aceitar`)
}

export async function recusarSolicitacao(idSolicitante: number): Promise<void> {
    await api.delete(`/api/relacoes/solicitacoes/${idSolicitante}`)
}
