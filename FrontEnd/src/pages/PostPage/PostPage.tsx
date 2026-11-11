import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import ButtonBack from '../../components/common/ButtonBack'
import NotFoundMessage from '../../components/common/NotFoundMessage'
import ListarPostagens from '../../components/postagens/ListarPostagens'
import Postagem from '../../components/postagens/Postagem'
import { useExcluirPostagem } from '../../hooks/usePostagemActions'
import { useRespostas } from '../../hooks/usePostagens'
import { getErrorMessage, getErrorStatus } from '../../lib/errors'
import { useToast } from '../../hooks/useToast'
import * as postagemService from '../../services/postagemService'
import type { Postagem as PostagemModel } from '../../types/api'

function PostPageView({ id }: { id: number }) {
    const navigate = useNavigate()
    const toast = useToast()
    const [postagem, setPostagem] = useState<PostagemModel | null>(null)
    const [naoEncontrada, setNaoEncontrada] = useState(false)
    const [versaoRespostas, setVersaoRespostas] = useState(0)
    const { postagens: respostas, setPostagens: setRespostas } = useRespostas(id, versaoRespostas)

    useEffect(() => {
        let ignore = false
        setPostagem(null)
        setNaoEncontrada(false)

        postagemService
            .buscar(id)
            .then((resultado) => {
                if (!ignore) setPostagem(resultado)
            })
            .catch((error: unknown) => {
                if (ignore) return
                setNaoEncontrada(true)
                if (getErrorStatus(error) !== 404) toast.error(getErrorMessage(error))
            })

        return () => {
            ignore = true
        }
    }, [id, toast])

    const aoExcluir = useCallback(() => navigate(-1), [navigate])
    const excluir = useExcluirPostagem(aoExcluir)

    return (
        <div className="post-page">
            <ButtonBack />
            {postagem && (
                <Postagem
                    postagem={postagem}
                    onExcluir={(postId) => void excluir(postId)}
                    onResposta={() => setVersaoRespostas((atual) => atual + 1)}
                />
            )}
            {naoEncontrada && <NotFoundMessage msg="Publicação não encontrada" />}
            {postagem && respostas.length > 0 && (
                <ListarPostagens postagens={respostas} setPostagens={setRespostas} idReferencia={postagem.id} />
            )}
        </div>
    )
}

function PostPage() {
    const { id } = useParams()
    const postId = Number(id)

    if (!Number.isInteger(postId)) return <NotFoundMessage msg="Publicação não encontrada" />

    return <PostPageView key={postId} id={postId} />
}

export default PostPage
