import ListarPostagens from '../../components/postagens/ListarPostagens'
import NoContent from '../../components/common/NoContent'
import { useCurrentUser } from '../../hooks/useAuth'
import { useFeed } from '../../hooks/usePostagens'

function Home() {
    const user = useCurrentUser()
    const { postagens, setPostagens, carregando } = useFeed(user.id)

    if (carregando) return null

    return (
        <div className="home">
            {postagens.length > 0 ? (
                <ListarPostagens postagens={postagens} setPostagens={setPostagens} />
            ) : (
                <div className="px-3">
                    <NoContent msg="Não há postagens para ver, siga mais usuários para ver suas postagens" />
                </div>
            )}
        </div>
    )
}

export default Home
