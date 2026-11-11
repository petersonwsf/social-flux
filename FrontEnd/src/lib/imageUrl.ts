import { API_BASE_URL, API_URL } from '../config/env'

/** Prefixo das chaves de foto de perfil geradas pelo profile_service (av_<uuid>.jpg). */
const AVATAR_PREFIX = 'av_'

/**
 * URL de uma imagem de um usuário. Fotos de perfil (chave `av_…`) são servidas pelo profile_service em
 * /media/avatars (públicas e imutáveis); imagens de postagem continuam no backend legado em /api/img/{usuario}/{arquivo}.
 */
export function getUserImageUrl(userId: number, filename: string): string {
    if (filename.startsWith(AVATAR_PREFIX)) return `${API_URL}/media/avatars/${filename}`
    return `${API_BASE_URL}/img/${userId}/${filename}`
}
