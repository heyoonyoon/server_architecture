import type { ReactNode } from 'react'

type ConfirmButtonProps = {
  message: string
  onConfirm: () => void
  disabled?: boolean
  className?: string
  children: ReactNode
}

export function ConfirmButton({ message, onConfirm, disabled, className, children }: ConfirmButtonProps) {
  function handleClick() {
    if (window.confirm(message)) onConfirm()
  }

  return (
    <button type="button" className={className} onClick={handleClick} disabled={disabled}>
      {children}
    </button>
  )
}
