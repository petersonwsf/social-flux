import { useCallback, useEffect, useState } from 'react'
import * as relacaoService from '../services/relacaoService'
import * as notificationService from '../services/notificationService'
import { getErrorMessage } from '../lib/errors'
import { useToast } from './useToast'
import type { Relacao, Usuario } from '../types/api'

/** Solicitações de seguir recebidas pelo usuário, com ações de aceitar/recusar. */
export function useRelacoesPendentes(user: Usuario) {
    const toast = useToast()
    const [pendentes, setPendentes] = useState<Relacao[]>([])

    useEffect(() => {
        let ignore = false

        relacaoService
            .buscarPendentes(user.id)
            .then((lista) => {
                if (!ignore) setPendentes(lista)
            })
            .catch((error: unknown) => {
                if (!ignore) toast.error(getErrorMessage(error))
            })

        return () => {
            ignore = true
        }
    }, [user.id, toast])

    const aceitar = useCallback(
        async (relacao: Relacao) => {
            try {
                await relacaoService.aceitar(relacao.id)
                setPendentes((lista) => lista.filter((item) => item.id !== relacao.id))
                notificationService.notificarSeguidor(relacao.seguidor, relacao.seguido).catch(() => undefined)
            } catch (error) {
                toast.error(getErrorMessage(error))
            }
        },
        [toast],
    )

    const recusar = useCallback(
        async (relacao: Relacao) => {
            try {
                await relacaoService.remover(relacao.id)
                setPendentes((lista) => lista.filter((item) => item.id !== relacao.id))
            } catch (error) {
                toast.error(getErrorMessage(error))
            }
        },
        [toast],
    )

    return { pendentes, aceitar, recusar }
}
