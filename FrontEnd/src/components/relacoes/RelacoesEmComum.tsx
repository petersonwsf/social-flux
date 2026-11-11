import { useRelacoesEmComum } from '../../hooks/useRelacoesEmComum'
import Avatar from '../common/Avatar'
import ModalListUsers from '../modals/ModalListUsers'
import type { Usuario } from '../../types/api'

interface RelacoesEmComumProps {
    user: Usuario
    perfil: Usuario
}

const MODAL_ID = 'relacoesEmComum'
const MAX_VISIVEIS = 3

function RelacoesEmComum({ user, perfil }: RelacoesEmComumProps) {
    const emComum = useRelacoesEmComum(user, perfil)
    const visiveis = emComum.slice(0, MAX_VISIVEIS)
    const restantes = emComum.length - MAX_VISIVEIS

    return (
        <div>
            {emComum.length > 0 && (
                <button
                    type="button"
                    className="btn p-0 d-flex align-items-center text-start"
                    data-bs-toggle="modal"
                    data-bs-target={`#${MODAL_ID}`}
                >
                    <div className="fotos d-flex position-relative align-items-center me-5">
                        {visiveis.map((usuario, index) => (
                            <Avatar
                                key={usuario.id}
                                user={usuario}
                                alt=""
                                className="foto-relacao-em-comum"
                                style={{ position: 'absolute', left: `${index * 12}px`, zIndex: MAX_VISIVEIS - index }}
                            />
                        ))}
                    </div>
                    <p className="relacao-em-comum mb-0 text-break">
                        Seguido por {visiveis.map((usuario) => usuario.username).join(', ')}
                        {restantes > 0 && ` e por mais ${restantes} ${restantes > 1 ? 'pessoas' : 'pessoa'}`}
                    </p>
                </button>
            )}
            <ModalListUsers title="Relações em comum" msgDefault="Não há relações em comum" id={MODAL_ID} users={emComum} />
        </div>
    )
}

export default RelacoesEmComum
