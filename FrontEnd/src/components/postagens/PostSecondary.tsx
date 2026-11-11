import { Link } from 'react-router-dom'
import { calcTempPost } from '../../lib/dates'
import { getUserImageUrl } from '../../lib/imageUrl'
import { useCurrentUser } from '../../hooks/useAuth'
import { usePostagemInteracoes } from '../../hooks/usePostagemInteracoes'
import Avatar from '../common/Avatar'
import FormComentar from '../forms/FormComentar'
import DropdownActions from './DropdownActions'
import { CommentButton, LikeButton } from './PostActions'
import type { Postagem } from '../../types/api'

interface PostSecondaryProps {
    postagem: Postagem
    onExcluir: (id: number) => void
    /** Id da postagem em foco; evita repetir a postagem pai quando ela já é exibida. */
    idReferencia?: number
}

/** Card compacto de postagem, usado em listas e como contexto da postagem pai. */
function PostSecondary({ postagem, onExcluir, idReferencia }: PostSecondaryProps) {
    const user = useCurrentUser()
    const { curtidas, respostas, comentando, alternarComentario, comentar } = usePostagemInteracoes(postagem, user)
    const pai = postagem.postagem
    const autor = postagem.usuario

    return (
        <>
            {pai && idReferencia !== undefined && idReferencia !== pai.id && (
                <PostSecondary postagem={pai} onExcluir={onExcluir} idReferencia={idReferencia} />
            )}
            <article className="post-secondary" id={`post-${postagem.id}`}>
                <div className="d-flex ps-2 ps-lg-4 pt-2 pb-1">
                    <Link to={`/perfil/${autor.username}`} className="flex-shrink-0">
                        <Avatar user={autor} className="foto-perfil-postagem" />
                    </Link>
                    <div className="d-flex flex-column w-100 ps-2 min-w-0">
                        <div className="d-flex infos-user justify-content-between w-100 align-items-center">
                            <div className="d-flex text-truncate align-items-center">
                                <Link to={`/perfil/${autor.username}`} className="me-2 text-decoration-none d-flex text-truncate">
                                    {autor.nome}
                                    <span className="ms-2 text-truncate username-muted">@{autor.username}</span>
                                </Link>
                                <span className="text-secondary">{calcTempPost(postagem.data_postagem)}</span>
                            </div>
                            {autor.id === user.id && <DropdownActions onExcluir={() => onExcluir(postagem.id)} />}
                        </div>
                        <Link className="text-decoration-none text-body mt-1" to={`/postagem/${postagem.id}`}>
                            {pai && (
                                <p className="text-secondary referente">
                                    Em resposta a <span className="text-primary">@{pai.usuario.username}</span>
                                </p>
                            )}
                            {postagem.legenda && <p className="legenda text-break pe-2">{postagem.legenda}</p>}
                            {postagem.imagem && (
                                <img
                                    src={getUserImageUrl(autor.id, postagem.imagem)}
                                    className="post-image"
                                    alt="Imagem da postagem"
                                />
                            )}
                        </Link>
                        <div className="actions mt-1 d-flex align-items-center">
                            <LikeButton curtida={curtidas.curtida} onToggle={() => void curtidas.alternar()} />
                            <span className="me-3">{curtidas.total}</span>
                            <CommentButton onClick={alternarComentario} />
                            <span>{respostas.total}</span>
                        </div>
                        {comentando && (
                            <div className="w-100 mt-4 pe-2">
                                <FormComentar user={user} idPostagem={postagem.id} onSubmit={comentar} />
                            </div>
                        )}
                    </div>
                </div>
            </article>
            <hr />
        </>
    )
}

export default PostSecondary
