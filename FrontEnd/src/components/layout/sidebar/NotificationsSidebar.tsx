import { useNotificacoes } from '../../../hooks/useNotificacoes'
import ListNotifications from '../../notifications/ListNotifications'
import SidebarBackLink from './SidebarBackLink'

function NotificationsSidebar({ onVoltar }: { onVoltar: () => void }) {
    const { notificacoes, marcarComoVistas } = useNotificacoes()

    return (
        <div>
            <SidebarBackLink
                onClick={() => {
                    marcarComoVistas()
                    onVoltar()
                }}
            />
            <ListNotifications notifications={notificacoes} />
        </div>
    )
}

export default NotificationsSidebar
