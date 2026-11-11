import { useEffect, useState, type Dispatch, type SetStateAction } from 'react'
import * as postagemService from '../services/postagemService'
import { getErrorMessage } from '../lib/errors'
import { useToast } from './useToast'
import type { Postagem } from '../types/api'

interface PostagensState {
    postagens: Postagem[]
    setPostagens: Dispatch<SetStateAction<Postagem[]>>
    carregando: boolean
}

function usePostagensCarregadas(carregar: () => Promise<Postagem[]>, deps: readonly unknown[]): PostagensState {
    const toast = useToast()
    const [postagens, setPostagens] = useState<Postagem[]>([])
    const [carregando, setCarregando] = useState(true)

    useEffect(() => {
        let ignore = false
        setCarregando(true)

        carregar()
            .then((lista) => {
                if (!ignore) setPostagens(lista)
            })
            .catch((error: unknown) => {
                if (!ignore) toast.error(getErrorMessage(error))
            })
            .finally(() => {
                if (!ignore) setCarregando(false)
            })

        return () => {
            ignore = true
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [toast, ...deps])

    return { postagens, setPostagens, carregando }
}

/** Postagens das pessoas seguidas pelo usuário. */
export function useFeed(idUsuario: number) {
    return usePostagensCarregadas(() => postagemService.buscarFeed(idUsuario), [idUsuario])
}

export function usePostagensUsuario(idUsuario: number, filtro: postagemService.FiltroPostagensUsuario) {
    return usePostagensCarregadas(() => postagemService.buscarDoUsuario(idUsuario, filtro), [idUsuario, filtro])
}

/** Respostas de uma postagem. */
export function useRespostas(idPostagem: number, versao = 0) {
    return usePostagensCarregadas(() => postagemService.buscarRespostas(idPostagem), [idPostagem, versao])
}
