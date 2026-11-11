import { Link } from 'react-router-dom'
import type { Relacao } from '../../types/api'

interface ActionsProfileProps {
    ehProprioPerfil: boolean
    relacao: Relacao | null
    onSeguir: () => void
    onCancelarSolicitacao: () => void
    /** Id do modal de confirmação para deixar de seguir. */
    modalDeixarDeSeguirId: string
}

function ActionsProfile({
    ehProprioPerfil,
    relacao,
    onSeguir,
    onCancelarSolicitacao,
    modalDeixarDeSeguirId,
}: ActionsProfileProps) {
    if (ehProprioPerfil) {
        return (
            <Link to="/editar" className="btn btn-primary">
                Editar perfil
            </Link>
        )
    }

    if (!relacao) {
        return (
            <button type="button" className="btn btn-primary d-flex align-items-center" onClick={onSeguir}>
                <ion-icon name="person-add-outline"></ion-icon> Seguir
            </button>
        )
    }

    if (relacao.aceito) {
        return (
            <button
                type="button"
                className="btn btn-outline-primary d-flex align-items-center"
                data-bs-toggle="modal"
                data-bs-target={`#${modalDeixarDeSeguirId}`}
            >
                <ion-icon name="people-outline"></ion-icon> Seguindo
            </button>
        )
    }

    return (
        <button type="button" className="btn btn-secondary d-flex align-items-center" onClick={onCancelarSolicitacao}>
            Solicitado
        </button>
    )
}

export default ActionsProfile
