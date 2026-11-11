import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { NotificationsContext, type NotificationsContextValue } from './notificationsContext'
import { getErrorMessage } from '../lib/errors'
import * as notificationService from '../services/notificationService'
import { useCurrentUser } from '../hooks/useAuth'
import { useToast } from '../hooks/useToast'
import type { Notification } from '../types/api'

export function NotificationsProvider({ children }: { children: ReactNode }) {
    const user = useCurrentUser()
    const toast = useToast()
    const [notificacoes, setNotificacoes] = useState<Notification[]>([])
    const [naoVistas, setNaoVistas] = useState(0)

    useEffect(() => {
        let ignore = false

        Promise.all([notificationService.listar(user.id), notificationService.contarNaoVistas(user.id)])
            .then(([lista, total]) => {
                if (ignore) return
                setNotificacoes(lista)
                setNaoVistas(total)
            })
            .catch((error: unknown) => {
                if (!ignore) toast.error(getErrorMessage(error))
            })

        return () => {
            ignore = true
        }
    }, [user.id, toast])

    const zerarContador = useCallback(() => setNaoVistas(0), [])

    const marcarComoVistas = useCallback(() => {
        notificationService
            .marcarComoVistas(user.id)
            .then(() => setNotificacoes((lista) => lista.map((item) => ({ ...item, viewed: true }))))
            .catch((error: unknown) => toast.error(getErrorMessage(error)))
    }, [user.id, toast])

    const value = useMemo<NotificationsContextValue>(
        () => ({ notificacoes, naoVistas, zerarContador, marcarComoVistas }),
        [notificacoes, naoVistas, zerarContador, marcarComoVistas],
    )

    return <NotificationsContext.Provider value={value}>{children}</NotificationsContext.Provider>
}
