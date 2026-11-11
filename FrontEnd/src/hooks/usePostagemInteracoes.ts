import { useCallback, useState } from 'react'
import { useCurtidas } from './useCurtidas'
import { useNumeroRespostas } from './useNumeroRespostas'
import { useCriarPostagem } from './usePostagemActions'
import type { CriarPostagemPayload, Postagem, Usuario } from '../types/api'

/** Curtidas, contagem de respostas e formulário de comentário de um card de postagem. */
export function usePostagemInteracoes(postagem: Postagem, user: Usuario, onResposta?: () => void) {
    const curtidas = useCurtidas(postagem, user)
    const respostas = useNumeroRespostas(postagem.id)
    const [comentando, setComentando] = useState(false)

    const { incrementar } = respostas
    const handleCriada = useCallback(() => {
        incrementar()
        setComentando(false)
        onResposta?.()
    }, [incrementar, onResposta])

    const { criarPostagem } = useCriarPostagem(handleCriada)

    const comentar = useCallback(
        (values: CriarPostagemPayload) => criarPostagem(values, postagem),
        [criarPostagem, postagem],
    )

    return {
        curtidas,
        respostas,
        comentando,
        alternarComentario: () => setComentando((atual) => !atual),
        comentar,
    }
}
