import { createContext } from 'react'
import type { Notification } from '../types/api'

export interface NotificationsContextValue {
    notificacoes: Notification[]
    naoVistas: number
    /** Zera o contador do badge imediatamente (ao abrir a lista). */
    zerarContador: () => void
    /** Marca todas como vistas no backend (ao sair da lista). */
    marcarComoVistas: () => void
}

export const NotificationsContext = createContext<NotificationsContextValue | null>(null)
