import { api } from '../lib/api'
import type { Notification, NotificationPayload, Postagem, Usuario, UsuarioResumo } from '../types/api'

export async function listar(idUsuario: number): Promise<Notification[]> {
    const { data } = await api.get<Notification[]>('/api/notification', { params: { userId: idUsuario } })
    return data
}

export async function contarNaoVistas(idUsuario: number): Promise<number> {
    const { data } = await api.get<number>('/api/notification', { params: { userId: idUsuario, count: true } })
    return data
}

export async function marcarComoVistas(idUsuario: number): Promise<void> {
    await api.patch(`/api/notification/${idUsuario}`)
}

async function enviar(payload: NotificationPayload): Promise<void> {
    await api.post('/api/notification', payload)
}

export function notificarCurtida(sender: Usuario, post: Postagem): Promise<void> {
    return enviar({
        sender: JSON.stringify(sender),
        receiver: JSON.stringify(post.autor),
        title: 'curtiu sua publicação',
        description: `Curtiu seu post: ${post.legenda ?? (post.imagem ? 'imagem' : '')}`,
        category: 'LIKE',
        id_post: post.id,
    })
}

export function notificarComentario(sender: Usuario, post: Postagem, comentario: string): Promise<void> {
    return enviar({
        sender: JSON.stringify(sender),
        receiver: JSON.stringify(post.autor),
        title: 'comentou na sua publicação',
        description: comentario || null,
        category: 'COMENT',
        id_post: post.id,
    })
}

export function notificarSeguidor(follower: UsuarioResumo, followed: UsuarioResumo): Promise<void> {
    return enviar({
        sender: JSON.stringify(follower),
        receiver: JSON.stringify(followed),
        title: 'começou a seguir você',
        description: '',
        category: 'FOLLOW',
        id_post: null,
    })
}
