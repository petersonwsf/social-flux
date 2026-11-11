import { useEffect, useState } from 'react'
import ButtonBack from '../../components/common/ButtonBack'
import TabMenu from '../../components/common/TabMenu'
import ListNotifications from '../../components/notifications/ListNotifications'
import ListaRelacoesNaoAceitas from '../../components/relacoes/ListaRelacoesNaoAceitas'
import { useCurrentUser } from '../../hooks/useAuth'
import { useNotificacoes } from '../../hooks/useNotificacoes'
import { useRelacoesPendentes } from '../../hooks/useRelacoesPendentes'

type Aba = 'notificacoes' | 'solicitacoes'

const TABS: ReadonlyArray<{ id: Aba; label: string }> = [
    { id: 'notificacoes', label: 'Notificações' },
    { id: 'solicitacoes', label: 'Solicitações' },
]

function Notifications() {
    const user = useCurrentUser()
    const { notificacoes, zerarContador, marcarComoVistas } = useNotificacoes()
    const { pendentes, aceitar, recusar } = useRelacoesPendentes(user)
    const [aba, setAba] = useState<Aba>('notificacoes')

    // Ao abrir a tela o badge zera; ao sair, as notificações são marcadas como vistas.
    useEffect(() => {
        zerarContador()
        return marcarComoVistas
    }, [zerarContador, marcarComoVistas])

    return (
        <div className="notifications-page">
            <div className="mb-2">
                <ButtonBack />
            </div>
            <TabMenu tabs={TABS} active={aba} onChange={setAba} />
            {aba === 'notificacoes' ? (
                <ListNotifications notifications={notificacoes} />
            ) : (
                <ListaRelacoesNaoAceitas relacoes={pendentes} onAceitar={aceitar} onRecusar={recusar} />
            )}
        </div>
    )
}

export default Notifications
