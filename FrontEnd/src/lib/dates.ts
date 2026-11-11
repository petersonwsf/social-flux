import type { LocalDateTimeArray } from '../types/api'

function toDate([ano = 1970, mes = 1, dia = 1, hora = 0, minuto = 0, segundo = 0]: LocalDateTimeArray): Date {
    return new Date(Date.UTC(ano, mes - 1, dia, hora, minuto, segundo))
}

/** Tempo relativo curto (ex.: "5 min", "2 h", "3 d") ou a data para posts com mais de uma semana. */
export function calcTempPost(dataPostagem: LocalDateTimeArray): string {
    const date = toDate(dataPostagem)
    const diffSeconds = (Date.now() - date.getTime()) / 1000
    const diffMinutes = diffSeconds / 60
    const diffHours = diffMinutes / 60
    const diffDays = diffHours / 24

    if (diffSeconds < 60) return `${Math.max(0, Math.floor(diffSeconds))} seg`
    if (diffMinutes < 60) return `${Math.floor(diffMinutes)} min`
    if (diffHours < 24) return `${Math.floor(diffHours)} h`
    if (diffDays < 7) return `${Math.floor(diffDays)} d`
    return new Intl.DateTimeFormat('pt-BR').format(date)
}

export function formatDate(dataPostagem: LocalDateTimeArray): string {
    return toDate(dataPostagem).toLocaleString('pt-BR', {
        hour: '2-digit',
        minute: '2-digit',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
    })
}
