import { api } from '../lib/api'
import type { CriarPostagemPayload, Mensagem, Postagem } from '../types/api'

export type FiltroPostagensUsuario = 'posts' | 'interacoes' | 'midia'

export async function criar(values: CriarPostagemPayload, idPostagemPai?: number): Promise<Mensagem> {
    const formData = new FormData()
    formData.append('legenda', values.legenda)
    if (values.imagem) formData.append('imagem', values.imagem)
    if (idPostagemPai !== undefined) formData.append('id_postagem', String(idPostagemPai))

    const { data } = await api.post<Mensagem>('/api/postagem', formData)
    return data
}

export async function remover(id: number): Promise<Mensagem> {
    const { data } = await api.delete<Mensagem>(`/api/postagem/${id}`)
    return data
}

export async function buscar(id: number): Promise<Postagem> {
    const { data } = await api.get<Postagem>(`/api/postagem/${id}`)
    return data
}

export async function buscarRespostas(id: number): Promise<Postagem[]> {
    const { data } = await api.get<Postagem[]>('/api/postagem', {
        params: { id_postagem: id, responses: true },
    })
    return data
}

/** Postagens das pessoas que o usuário segue. */
export async function buscarFeed(idSeguidor: number): Promise<Postagem[]> {
    const { data } = await api.get<Postagem[]>('/api/postagem', { params: { id_seguidor: idSeguidor } })
    return data
}

export async function buscarDoUsuario(idUsuario: number, filtro: FiltroPostagensUsuario): Promise<Postagem[]> {
    const params: Record<string, string | number | boolean> = { id_user: idUsuario }
    if (filtro === 'interacoes') params.allPosts = true
    if (filtro === 'midia') params.mediaPosts = true

    const { data } = await api.get<Postagem[]>('/api/postagem', { params })
    return data
}
