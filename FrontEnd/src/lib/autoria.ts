import type { Usuario, UsuarioResumo } from '../types/api'

/** O conteúdo é do usuário logado? Compara pelo UUID da conta; cai no id inteiro legado se algum lado não o tiver. */
export function ehDoUsuario(autor: UsuarioResumo, user: Usuario): boolean {
    if (autor.accountId && user.accountId) return autor.accountId === user.accountId
    return autor.id === user.id
}
