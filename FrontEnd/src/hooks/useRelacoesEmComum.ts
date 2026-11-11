import { useEffect, useState } from 'react'
import * as relacaoService from '../services/relacaoService'
import { getErrorMessage } from '../lib/errors'
import { useToast } from './useToast'
import type { Usuario } from '../types/api'

/** Usuários que o logado e o outro usuário têm em comum. */
export function useRelacoesEmComum(user: Usuario, outro: Usuario) {
    const toast = useToast()
    const [emComum, setEmComum] = useState<Usuario[]>([])

    useEffect(() => {
        if (user.id === outro.id) return

        let ignore = false

        relacaoService
            .buscarEmComum(user.id, outro.id)
            .then((lista) => {
                if (!ignore) setEmComum(lista)
            })
            .catch((error: unknown) => {
                if (!ignore) toast.error(getErrorMessage(error))
            })

        return () => {
            ignore = true
        }
    }, [user.id, outro.id, toast])

    return emComum
}
