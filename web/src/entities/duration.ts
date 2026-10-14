export function formatDuration(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

export function formatWait(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return minutes > 0 ? `약 ${minutes}분 ${seconds}초` : `약 ${seconds}초`
}

export function formatTimeOfDay(epochMs: number): string {
  const time = new Date(epochMs)
  return [time.getHours(), time.getMinutes(), time.getSeconds()].map((part) => String(part).padStart(2, '0')).join(':')
}
