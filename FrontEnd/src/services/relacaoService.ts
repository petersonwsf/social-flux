import { api } from '../lib/api'
import type { Relacao, Usuario } from '../types/api'

/** O backend responde 200 com corpo vazio quando não há relação. */
export async function buscar(idSeguidor: number, idSeguido: number): Promise<Relacao | null> {
    const { data } = await api.get<Relacao | ''>('/api/relacao', {
        params: { id_seguidor: idSeguidor, id_seguido: idSeguido },
    })
    return data || null
}

/** Relações em que o usuário é o seguidor (quem ele segue). */
export async function buscarSeguidos(idUsuario: number): Promise<Relacao[]> {
    const { data } = await api.get<Relacao[]>('/api/relacao', { params: { id_seguidor: idUsuario } })
    return data
}

export async function buscarSeguidores(idUsuario: number): Promise<Relacao[]> {
    const { data } = await api.get<Relacao[]>('/api/relacao', { params: { id_seguido: idUsuario } })
    return data
}

export async function buscarPendentes(idUsuario: number): Promise<Relacao[]> {
    const { data } = await api.get<Relacao[]>('/api/relacao', {
        params: { id_seguido: idUsuario, pending: true },
    })
    return data
}

export async function buscarEmComum(idUsuario: number, idUsuarioPerfil: number): Promise<Usuario[]> {
    const { data } = await api.get<Usuario[]>('/api/relacao/compartilhado', {
        params: { id_usuario: idUsuario, id_usuario_perfil: idUsuarioPerfil },
    })
    return data
}

export async function criar(user: Usuario, profileUser: Usuario): Promise<Relacao> {
    const { data } = await api.post<Relacao>('/api/relacao', { user, profileUser })
    return data
}

export async function remover(id: number): Promise<void> {
    await api.delete(`/api/relacao/${id}`)
}

export async function aceitar(id: number): Promise<void> {
    await api.patch(`/api/relacao/${id}`)
}
