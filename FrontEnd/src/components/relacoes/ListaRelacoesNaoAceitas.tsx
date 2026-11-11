import CardRelacaoNaoAceita from './CardRelacaoNaoAceita'
import type { Relacao } from '../../types/api'

interface ListaRelacoesNaoAceitasProps {
    relacoes: Relacao[]
    onAceitar: (relacao: Relacao) => void
    onRecusar: (relacao: Relacao) => void
}

function ListaRelacoesNaoAceitas({ relacoes, onAceitar, onRecusar }: ListaRelacoesNaoAceitasProps) {
    if (relacoes.length === 0) {
        return (
            <div className="text-center px-3">
                <p className="lead">Não há solicitações de amizade</p>
            </div>
        )
    }

    return (
        <ul className="nav nav-pills flex-column mt-1">
            {relacoes.map((relacao) => (
                <li key={relacao.id}>
                    <CardRelacaoNaoAceita relacao={relacao} onAceitar={onAceitar} onRecusar={onRecusar} />
                </li>
            ))}
        </ul>
    )
}

export default ListaRelacoesNaoAceitas
