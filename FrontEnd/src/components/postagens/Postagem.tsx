import { Link } from 'react-router-dom'
import { formatDate } from '../../lib/dates'
import { getUserImageUrl } from '../../lib/imageUrl'
import { useCurrentUser } from '../../hooks/useAuth'
import { usePostagemInteracoes } from '../../hooks/usePostagemInteracoes'
import Avatar from '../common/Avatar'
import FormComentar from '../forms/FormComentar'
import DropdownActions from './DropdownActions'
import PostSecondary from './PostSecondary'
import { CommentButton, LikeButton } from './PostActions'
import type { Postagem as PostagemModel } from '../../types/api'

interface PostagemProps {
    postagem: PostagemModel
    onExcluir: (id: number) => void
    /** Chamado quando uma resposta é criada (ex.: para recarregar a lista de respostas). */
    onResposta?: () => void
}

/** Card completo de uma postagem (página de detalhe). */
function Postagem({ postagem, onExcluir, onResposta }: PostagemProps) {
    const user = useCurrentUser()
    const { curtidas, respostas, comentando, alternarComentario, comentar } = usePostagemInteracoes(
        postagem,
        user,
        onResposta,
    )
    const pai = postagem.postagem
    const autor = postagem.usuario

    return (
        <>
            {pai && <PostSecondary postagem={pai} onExcluir={onExcluir} idReferencia={postagem.id} />}
            <article className="post my-3" id={`post-${postagem.id}`}>
                <div className="d-flex px-3">
                    <Link to={`/perfil/${autor.username}`} className="flex-shrink-0">
                        <Avatar user={autor} className="foto-perfil" />
                    </Link>
                    <div className="w-100 ps-3 d-flex flex-column min-w-0">
                        <div className="d-flex infos-user mb-1 justify-content-between">
                            <Link
                                className="d-flex text-decoration-none align-items-center w-75 text-truncate"
                                to={`/perfil/${autor.username}`}
                            >
                                {autor.nome}
                                <span className="mx-2 text-truncate username-muted">@{autor.username}</span>
                            </Link>
                            {autor.id === user.id && <DropdownActions onExcluir={() => onExcluir(postagem.id)} />}
                        </div>
                        {pai && (
                            <p className="text-secondary referente">
                                Em resposta a{' '}
                                <Link to={`/perfil/${pai.usuario.username}`} className="text-decoration-none">
                                    @{pai.usuario.username}
                                </Link>
                            </p>
                        )}
                        {postagem.legenda && <p className="legenda text-break">{postagem.legenda}</p>}
                        {postagem.imagem && (
                            <img
                                src={getUserImageUrl(autor.id, postagem.imagem)}
                                className="post-image mt-2"
                                alt="Imagem da postagem"
                            />
                        )}
                        <p className="text-secondary data-publicacao mt-2">{formatDate(postagem.data_postagem)}</p>
                        <div className="infos-post d-flex">
                            <span className="me-3">{curtidas.total} Curtidas</span>
                            <span>{respostas.total} Comentários</span>
                        </div>
                    </div>
                </div>
                <hr />
                <div className="actions d-flex px-4">
                    <LikeButton curtida={curtidas.curtida} onToggle={() => void curtidas.alternar()} />
                    <CommentButton onClick={alternarComentario} />
                </div>
                {comentando && (
                    <div className="px-3 my-3">
                        <FormComentar user={user} idPostagem={postagem.id} onSubmit={comentar} />
                    </div>
                )}
                <hr />
            </article>
        </>
    )
}

export default Postagem
