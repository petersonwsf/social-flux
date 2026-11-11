import type { Curtida } from '../../types/api'

interface LikeButtonProps {
    curtida: Curtida | null
    onToggle: () => void
}

export function LikeButton({ curtida, onToggle }: LikeButtonProps) {
    return (
        <button
            type="button"
            onClick={onToggle}
            className={`btn btn-icon post-action ${curtida ? 'is-liked' : ''}`}
            aria-pressed={curtida !== null}
            aria-label={curtida ? 'Remover curtida' : 'Curtir'}
        >
            <ion-icon name={curtida ? 'heart' : 'heart-outline'}></ion-icon>
        </button>
    )
}

export function CommentButton({ onClick }: { onClick: () => void }) {
    return (
        <button type="button" onClick={onClick} className="btn btn-icon post-action" aria-label="Comentar">
            <ion-icon name="chatbubble-outline"></ion-icon>
        </button>
    )
}
