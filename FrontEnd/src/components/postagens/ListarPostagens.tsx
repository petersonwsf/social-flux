import { useCallback, type Dispatch, type SetStateAction } from 'react'
import { useExcluirPostagem } from '../../hooks/usePostagemActions'
import PostSecondary from './PostSecondary'
import type { Postagem } from '../../types/api'

interface ListarPostagensProps {
    postagens: Postagem[]
    setPostagens: Dispatch<SetStateAction<Postagem[]>>
    idReferencia?: number
}

function ListarPostagens({ postagens, setPostagens, idReferencia }: ListarPostagensProps) {
    const removerDaLista = useCallback(
        (id: number) => setPostagens((lista) => lista.filter((postagem) => postagem.id !== id)),
        [setPostagens],
    )
    const excluir = useExcluirPostagem(removerDaLista)

    return (
        <div>
            {postagens.map((postagem) => (
                <PostSecondary
                    key={postagem.id}
                    postagem={postagem}
                    onExcluir={(id) => void excluir(id)}
                    idReferencia={idReferencia}
                />
            ))}
            <div className="w-100 text-center">
                <p className="text-secondary">°</p>
            </div>
        </div>
    )
}

export default ListarPostagens
