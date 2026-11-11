import { useCallback, useEffect, useState } from 'react'
import * as curtidaService from '../services/curtidaService'
import * as notificationService from '../services/notificationService'
import { getErrorMessage } from '../lib/errors'
import { useToast } from './useToast'
import type { Curtida, Postagem, Usuario } from '../types/api'

/** Estado de curtida de uma postagem para o usuário logado (curtida própria + total). */
export function useCurtidas(postagem: Postagem, user: Usuario) {
    const toast = useToast()
    const [curtida, setCurtida] = useState<Curtida | null>(null)
    const [total, setTotal] = useState(0)

    useEffect(() => {
        let ignore = false

        Promise.all([curtidaService.contar(postagem.id), curtidaService.buscar(user.id, postagem.id)])
            .then(([quantidade, propria]) => {
                if (ignore) return
                setTotal(quantidade)
                setCurtida(propria)
            })
            .catch((error: unknown) => {
                if (!ignore) toast.error(getErrorMessage(error))
            })

        return () => {
            ignore = true
        }
    }, [postagem.id, user.id, toast])

    const alternar = useCallback(async () => {
        try {
            if (curtida) {
                await curtidaService.remover(curtida.id)
                setCurtida(null)
                setTotal((atual) => atual - 1)
            } else {
                const criada = await curtidaService.criar(user.id, postagem.id)
                setCurtida(criada)
                setTotal((atual) => atual + 1)
                // A notificação é secundária: uma falha aqui não deve atrapalhar a curtida.
                notificationService.notificarCurtida(user, postagem).catch(() => undefined)
            }
        } catch (error) {
            toast.error(getErrorMessage(error))
        }
    }, [curtida, postagem, user, toast])

    return { curtida, total, alternar }
}
