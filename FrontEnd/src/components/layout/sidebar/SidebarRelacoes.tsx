import ListaRelacoesNaoAceitas from '../../relacoes/ListaRelacoesNaoAceitas'
import SidebarBackLink from './SidebarBackLink'
import type { Relacao } from '../../../types/api'

interface SidebarRelacoesProps {
    relacoes: Relacao[]
    onVoltar: () => void
    onAceitar: (relacao: Relacao) => void
    onRecusar: (relacao: Relacao) => void
}

function SidebarRelacoes({ relacoes, onVoltar, onAceitar, onRecusar }: SidebarRelacoesProps) {
    return (
        <div>
            <SidebarBackLink onClick={onVoltar} />
            <ListaRelacoesNaoAceitas relacoes={relacoes} onAceitar={onAceitar} onRecusar={onRecusar} />
        </div>
    )
}

export default SidebarRelacoes
