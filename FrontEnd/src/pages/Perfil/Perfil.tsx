import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import ButtonBack from '../../components/common/ButtonBack'
import Loading from '../../components/common/Loading'
import NotFoundMessage from '../../components/common/NotFoundMessage'
import ProtectedContent from '../../components/common/ProtectedContent'
import TabMenu from '../../components/common/TabMenu'
import ModalAction from '../../components/modals/ModalAction'
import ModalListUsers from '../../components/modals/ModalListUsers'
import InfoPerfil, {
    MODAL_DEIXAR_DE_SEGUIR,
    MODAL_SEGUIDORES,
    MODAL_SEGUIDOS,
} from '../../components/perfil/InfoPerfil'
import { useCurrentUser } from '../../hooks/useAuth'
import { usePerfil } from '../../hooks/usePerfil'
import { useRelacao } from '../../hooks/useRelacao'
import type { FiltroPostagensUsuario } from '../../services/postagemService'
import type { Usuario } from '../../types/api'
import PerfilPostagens from './PerfilPostagens'

const TABS: ReadonlyArray<{ id: FiltroPostagensUsuario; label: string }> = [
    { id: 'posts', label: 'Posts' },
    { id: 'interacoes', label: 'Posts e interações' },
    { id: 'midia', label: 'Mídia' },
]

function PerfilView({ perfil }: { perfil: Usuario }) {
    const user = useCurrentUser()
    const { relacao, seguidores, seguidos, seguir, deixarDeSeguir } = useRelacao(user, perfil)
    const [aba, setAba] = useState<FiltroPostagensUsuario>('posts')

    const podeVerConteudo = perfil.id === user.id || !perfil.privado || relacao?.aceito === true

    return (
        <div className="perfil">
            <div className="w-100 d-flex justify-content-between align-items-center">
                <ButtonBack />
                <Link to="/settings" className="d-flex d-lg-none btn btn-icon" aria-label="Configurações">
                    <ion-icon name="settings-outline"></ion-icon>
                </Link>
            </div>
            <InfoPerfil
                perfil={perfil}
                user={user}
                numeroSeguidores={seguidores.length}
                numeroSeguidos={seguidos.length}
                relacao={relacao}
                onSeguir={() => void seguir()}
                onCancelarSolicitacao={() => void deixarDeSeguir()}
            />
            <TabMenu tabs={TABS} active={aba} onChange={setAba} />
            <div className="mt-4 text-center">
                {podeVerConteudo ? (
                    <PerfilPostagens perfil={perfil} filtro={aba} />
                ) : (
                    <div className="mt-5">
                        <ProtectedContent />
                    </div>
                )}
            </div>
            <ModalAction
                id={MODAL_DEIXAR_DE_SEGUIR}
                title={`Deseja deixar de seguir @${perfil.username}?`}
                titleAction="Deixar de seguir"
                action={() => void deixarDeSeguir()}
                danger
            />
            <ModalListUsers id={MODAL_SEGUIDORES} title="Seguidores" msgDefault="Não há seguidores" users={seguidores} />
            <ModalListUsers id={MODAL_SEGUIDOS} title="Seguidos" msgDefault="Não há seguidos" users={seguidos} />
        </div>
    )
}

function Perfil() {
    const { username = '' } = useParams()
    const { perfil, naoEncontrado } = usePerfil(username)

    if (perfil) return <PerfilView key={perfil.id} perfil={perfil} />

    return (
        <>
            <ButtonBack />
            <div className="perfil d-flex justify-content-center align-items-center px-3">
                {naoEncontrado ? <NotFoundMessage msg="Usuário não encontrado" /> : <Loading />}
            </div>
        </>
    )
}

export default Perfil
