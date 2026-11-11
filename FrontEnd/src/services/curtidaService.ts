import { api } from '../lib/api'
import type { Curtida } from '../types/api'

export async function criar(idUsuario: number, idConteudo: number): Promise<Curtida> {
    const { data } = await api.post<Curtida>('/api/curtida', { id_usuario: idUsuario, id_conteudo: idConteudo })
    return data
}

/** O backend responde 200 com corpo vazio quando não há curtida. */
export async function buscar(idUsuario: number, idConteudo: number): Promise<Curtida | null> {
    const { data } = await api.get<Curtida | ''>('/api/curtida', {
        params: { id_usuario: idUsuario, id_conteudo: idConteudo },
    })
    return data || null
}

export async function remover(id: number): Promise<void> {
    await api.delete(`/api/curtida/${id}`)
}

export async function contar(idConteudo: number): Promise<number> {
    const { data } = await api.get<number>(`/api/curtida/numero/${idConteudo}`)
    return data
}
