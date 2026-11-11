import { useCallback, useEffect, useState } from 'react'
import * as relacaoService from '../services/relacaoService'
import * as notificationService from '../services/notificationService'
import { getErrorMessage } from '../lib/errors'
import { useToast } from './useToast'
import type { Relacao, Usuario } from '../types/api'

/** Relação entre o usuário logado e o dono do perfil, mais as listas de seguidores/seguidos do perfil. */
export function useRelacao(user: Usuario, perfil: Usuario) {
    const toast = useToast()
    const [relacao, setRelacao] = useState<Relacao | null>(null)
    const [seguidores, setSeguidores] = useState<Usuario[]>([])
    const [seguidos, setSeguidos] = useState<Usuario[]>([])

    useEffect(() => {
        let ignore = false

        const carregar = async () => {
            try {
                const [listaSeguidos, listaSeguidores, propria] = await Promise.all([
                    relacaoService.buscarSeguidos(perfil.id),
                    relacaoService.buscarSeguidores(perfil.id),
                    user.id !== perfil.id ? relacaoService.buscar(user.id, perfil.id) : Promise.resolve(null),
                ])
                if (ignore) return
                setSeguidos(listaSeguidos.map((item) => item.seguido))
                setSeguidores(listaSeguidores.map((item) => item.seguidor))
                setRelacao(propria)
            } catch (error) {
                if (!ignore) toast.error(getErrorMessage(error))
            }
        }

        void carregar()
        return () => {
            ignore = true
        }
    }, [user.id, perfil.id, toast])

    const seguir = useCallback(async () => {
        try {
            const criada = await relacaoService.criar(user, perfil)
            setRelacao(criada)
            if (criada.aceito) {
                setSeguidores((lista) => [...lista, user])
                notificationService.notificarSeguidor(user, perfil).catch(() => undefined)
            }
        } catch (error) {
            toast.error(getErrorMessage(error))
        }
    }, [user, perfil, toast])

    const deixarDeSeguir = useCallback(async () => {
        if (!relacao) return
        try {
            await relacaoService.remover(relacao.id)
            if (relacao.aceito) setSeguidores((lista) => lista.filter((item) => item.id !== user.id))
            setRelacao(null)
        } catch (error) {
            toast.error(getErrorMessage(error))
        }
    }, [relacao, user.id, toast])

    return { relacao, seguidores, seguidos, seguir, deixarDeSeguir }
}
