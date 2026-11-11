import { useCallback, useState } from 'react'
import * as postagemService from '../services/postagemService'
import * as notificationService from '../services/notificationService'
import { getErrorMessage } from '../lib/errors'
import { useToast } from './useToast'
import { useCurrentUser } from './useAuth'
import type { CriarPostagemPayload, Postagem } from '../types/api'

/** Cria uma postagem (ou uma resposta, quando `pai` é informado). */
export function useCriarPostagem(onCriada?: () => void) {
    const user = useCurrentUser()
    const toast = useToast()
    const [enviando, setEnviando] = useState(false)

    const criarPostagem = useCallback(
        async (values: CriarPostagemPayload, pai?: Postagem) => {
            setEnviando(true)
            try {
                const resposta = await postagemService.criar(values, pai?.id)
                toast.notify(resposta)
                if (pai) {
                    notificationService.notificarComentario(user, pai, values.legenda).catch(() => undefined)
                }
                onCriada?.()
            } catch (error) {
                toast.error(getErrorMessage(error))
            } finally {
                setEnviando(false)
            }
        },
        [user, toast, onCriada],
    )

    return { criarPostagem, enviando }
}

/** Exclui uma postagem e avisa o chamador para atualizar a tela. */
export function useExcluirPostagem(onExcluida: (id: number) => void) {
    const toast = useToast()

    return useCallback(
        async (id: number) => {
            try {
                const resposta = await postagemService.remover(id)
                toast.notify(resposta)
                onExcluida(id)
            } catch (error) {
                toast.error(getErrorMessage(error))
            }
        },
        [toast, onExcluida],
    )
}
