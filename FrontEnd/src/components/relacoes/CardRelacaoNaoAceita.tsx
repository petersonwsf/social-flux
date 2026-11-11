import { Link } from 'react-router-dom'
import Avatar from '../common/Avatar'
import type { Relacao } from '../../types/api'

interface CardRelacaoNaoAceitaProps {
    relacao: Relacao
    onAceitar: (relacao: Relacao) => void
    onRecusar: (relacao: Relacao) => void
}

function CardRelacaoNaoAceita({ relacao, onAceitar, onRecusar }: CardRelacaoNaoAceitaProps) {
    return (
        <div className="card-relacao d-flex justify-content-between align-items-center w-100">
            <Link to={`/perfil/${relacao.seguidor.username}`} className="nav-link d-flex align-items-center">
                <Avatar user={relacao.seguidor} size="30px" className="me-2" alt="" />
                {relacao.seguidor.username}
            </Link>
            <div className="d-flex">
                <button type="button" className="btn btn-icon text-success" onClick={() => onAceitar(relacao)} aria-label="Aceitar">
                    <ion-icon name="checkmark-outline"></ion-icon>
                </button>
                <button type="button" className="btn btn-icon text-danger" onClick={() => onRecusar(relacao)} aria-label="Recusar">
                    <ion-icon name="close-outline"></ion-icon>
                </button>
            </div>
        </div>
    )
}

export default CardRelacaoNaoAceita
