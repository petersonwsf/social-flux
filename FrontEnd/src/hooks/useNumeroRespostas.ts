import { useCallback, useEffect, useState } from 'react'
import * as postagemService from '../services/postagemService'
import { getErrorMessage } from '../lib/errors'
import { useToast } from './useToast'

/** Quantidade de respostas de uma postagem, com incremento local após comentar. */
export function useNumeroRespostas(idPostagem: number) {
    const toast = useToast()
    const [total, setTotal] = useState(0)

    useEffect(() => {
        let ignore = false

        postagemService
            .buscarRespostas(idPostagem)
            .then((respostas) => {
                if (!ignore) setTotal(respostas.length)
            })
            .catch((error: unknown) => {
                if (!ignore) toast.error(getErrorMessage(error))
            })

        return () => {
            ignore = true
        }
    }, [idPostagem, toast])

    const incrementar = useCallback(() => setTotal((atual) => atual + 1), [])

    return { total, incrementar }
}
