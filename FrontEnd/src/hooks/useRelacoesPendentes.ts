import { useCallback, useEffect, useState } from 'react'
import * as relacaoService from '../services/relacaoService'
import * as notificationService from '../services/notificationService'
import { getErrorMessage } from '../lib/errors'
import { useToast } from './useToast'
import type { ItemRelacao, Usuario } from '../types/api'

/** Solicitações de seguir recebidas pelo usuário (paginadas), com ações de aceitar/recusar. */
export function useRelacoesPendentes(user: Usuario) {
    const toast = useToast()
    const [pendentes, setPendentes] = useState<ItemRelacao[]>([])
    const [proximoCursor, setProximoCursor] = useState<string | null>(null)
    const [carregando, setCarregando] = useState(false)

    const carregar = useCallback(
        async (cursor?: string, ignorar?: () => boolean) => {
            setCarregando(true)
            try {
                const pagina = await relacaoService.listarSolicitacoes(cursor)
                if (ignorar?.()) return
                setPendentes((atual) => (cursor ? [...atual, ...pagina.itens] : pagina.itens))
                setProximoCursor(pagina.proximoCursor)
            } catch (error) {
                if (!ignorar?.()) toast.error(getErrorMessage(error))
            } finally {
                if (!ignorar?.()) setCarregando(false)
            }
        },
        [toast],
    )

    useEffect(() => {
        let ignore = false
        void carregar(undefined, () => ignore)
        return () => {
            ignore = true
        }
    }, [user.id, carregar])

    const carregarMais = useCallback(() => {
        if (proximoCursor && !carregando) void carregar(proximoCursor)
    }, [proximoCursor, carregando, carregar])

    const aceitar = useCallback(
        async (item: ItemRelacao) => {
            try {
                await relacaoService.aceitarSolicitacao(item.usuario.id)
                setPendentes((lista) => lista.filter((outro) => outro.usuario.id !== item.usuario.id))
                notificationService.notificarSeguidor(item.usuario, user).catch(() => undefined)
            } catch (error) {
                toast.error(getErrorMessage(error))
            }
        },
        [user, toast],
    )

    const recusar = useCallback(
        async (item: ItemRelacao) => {
            try {
                await relacaoService.recusarSolicitacao(item.usuario.id)
                setPendentes((lista) => lista.filter((outro) => outro.usuario.id !== item.usuario.id))
            } catch (error) {
                toast.error(getErrorMessage(error))
            }
        },
        [toast],
    )

    return { pendentes, temMais: proximoCursor !== null, carregarMais, aceitar, recusar }
}
