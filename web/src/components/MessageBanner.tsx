import type { Notice } from '../hooks/useNotice'

type MessageBannerProps = {
  notice: Notice | null
  onDismiss?: () => void
}

export function MessageBanner({ notice, onDismiss }: MessageBannerProps) {
  if (!notice) return null
  return (
    <div className={`banner ${notice.kind}`} role={notice.kind === 'error' ? 'alert' : 'status'}>
      <span>{notice.text}</span>
      {onDismiss && (
        <button type="button" className="close" aria-label="닫기" onClick={onDismiss}>
          ×
        </button>
      )}
    </div>
  )
}
