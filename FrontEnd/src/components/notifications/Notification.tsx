import { Link } from 'react-router-dom'
import Avatar from '../common/Avatar'
import type { Notification as NotificationModel, NotificationCategory } from '../../types/api'

const CATEGORY_ICON: Record<NotificationCategory, { name: string; className: string }> = {
    LIKE: { name: 'heart-outline', className: 'text-danger' },
    COMENT: { name: 'chatbubble-outline', className: 'text-primary' },
    FOLLOW: { name: 'person-add-outline', className: 'text-primary' },
}

function Notification({ notification }: { notification: NotificationModel }) {
    const icon = CATEGORY_ICON[notification.category]
    const destino =
        notification.category === 'FOLLOW' ? `/perfil/${notification.sender.username}` : `/postagem/${notification.id_post}`

    return (
        <Link
            className={`notification d-flex align-items-center border-bottom text-decoration-none py-2 pe-3 ${
                notification.viewed ? 'viewed' : 'no-viewed'
            }`}
            to={destino}
        >
            <div className="mx-2 d-flex align-items-center">
                <span className={`d-flex ${icon.className}`}>
                    <ion-icon name={icon.name}></ion-icon>
                </span>
                <Avatar user={notification.sender} size="30px" alt="" />
            </div>
            <p className="mb-0">
                <span className="fw-medium">{notification.sender.nome}</span> {notification.title}
            </p>
        </Link>
    )
}

export default Notification
