import Notification from './Notification'
import type { Notification as NotificationModel } from '../../types/api'

function ListNotifications({ notifications }: { notifications: NotificationModel[] }) {
    return (
        <div className="notifications">
            {notifications.map((notification) => (
                <Notification key={notification.id} notification={notification} />
            ))}
        </div>
    )
}

export default ListNotifications
