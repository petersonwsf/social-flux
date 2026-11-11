import { Link } from 'react-router-dom'
import Avatar from '../common/Avatar'
import type { Usuario } from '../../types/api'

interface ModalListUsersProps {
    id: string
    title: string
    users: Usuario[]
    msgDefault: string
}

function ModalListUsers({ id, title, users, msgDefault }: ModalListUsersProps) {
    return (
        <div className="modal fade" id={id} tabIndex={-1} aria-hidden="true">
            <div className="modal-dialog">
                <div className="modal-content">
                    <div className="modal-header">
                        <p className="display-6 mb-0">{title}</p>
                        <button type="button" className="btn-close" data-bs-dismiss="modal" aria-label="Fechar"></button>
                    </div>
                    <div className="modal-body list-users">
                        {users.length > 0 ? (
                            users.map((user) => (
                                <Link
                                    key={user.id}
                                    to={`/perfil/${user.username}`}
                                    className="d-flex align-items-center nav-link my-3"
                                    data-bs-dismiss="modal"
                                >
                                    <Avatar user={user} size="50px" className="me-3" alt="" />
                                    {user.username}
                                </Link>
                            ))
                        ) : (
                            <div className="text-center">
                                <p className="lead">{msgDefault}</p>
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </div>
    )
}

export default ModalListUsers
