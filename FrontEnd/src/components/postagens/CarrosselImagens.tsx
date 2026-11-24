import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { getPostImageUrl } from '../../lib/imageUrl'
import type { ImagemPostagem } from '../../types/api'

interface CarrosselImagensProps {
    imagens: ImagemPostagem[]
    /** Posição da imagem mostrada. */
    indice: number
    onMudar: (indice: number) => void
    onFechar: () => void
}

/**
 * Carrossel em tela cheia com as imagens completas (sem corte) de uma postagem. Fecha com Esc, no X ou clicando no fundo;
 * as setas do teclado e os botões trocam de imagem.
 */
function CarrosselImagens({ imagens, indice, onMudar, onFechar }: CarrosselImagensProps) {
    const total = imagens.length
    const atual = imagens[indice]

    useEffect(() => {
        const aoTeclar = (evento: KeyboardEvent) => {
            if (evento.key === 'Escape') onFechar()
            else if (evento.key === 'ArrowLeft' && indice > 0) onMudar(indice - 1)
            else if (evento.key === 'ArrowRight' && indice < total - 1) onMudar(indice + 1)
        }
        document.addEventListener('keydown', aoTeclar)
        const overflow = document.body.style.overflow
        document.body.style.overflow = 'hidden'
        return () => {
            document.removeEventListener('keydown', aoTeclar)
            document.body.style.overflow = overflow
        }
    }, [indice, total, onMudar, onFechar])

    if (!atual) return null

    return createPortal(
        <div
            className="carrossel"
            role="dialog"
            aria-modal="true"
            aria-label={`Imagem ${indice + 1} de ${total}`}
            onClick={onFechar}
        >
            <button type="button" className="carrossel-botao carrossel-fechar" aria-label="Fechar" onClick={onFechar}>
                <ion-icon name="close-outline"></ion-icon>
            </button>
            {total > 1 && <span className="carrossel-contador">{indice + 1} / {total}</span>}
            {indice > 0 && (
                <button
                    type="button"
                    className="carrossel-botao carrossel-anterior"
                    aria-label="Imagem anterior"
                    onClick={(evento) => {
                        evento.stopPropagation()
                        onMudar(indice - 1)
                    }}
                >
                    <ion-icon name="chevron-back-outline"></ion-icon>
                </button>
            )}
            <img
                key={atual.url}
                src={getPostImageUrl(atual.url)}
                alt={`Imagem ${indice + 1} de ${total}`}
                className="carrossel-imagem"
                onClick={(evento) => evento.stopPropagation()}
            />
            {indice < total - 1 && (
                <button
                    type="button"
                    className="carrossel-botao carrossel-proxima"
                    aria-label="Próxima imagem"
                    onClick={(evento) => {
                        evento.stopPropagation()
                        onMudar(indice + 1)
                    }}
                >
                    <ion-icon name="chevron-forward-outline"></ion-icon>
                </button>
            )}
        </div>,
        document.body,
    )
}

export default CarrosselImagens
