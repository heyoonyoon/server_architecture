import type { OfferingTimeDto } from '../api/dto'

const dayLabels: Record<string, string> = {
  MON: '월',
  TUE: '화',
  WED: '수',
  THU: '목',
  FRI: '금',
  SAT: '토',
  SUN: '일',
}

function formatClock(time: string): string {
  return time.slice(0, 5)
}

function formatTime({ dayOfWeek, startTime, endTime }: OfferingTimeDto): string {
  return `${dayLabels[dayOfWeek] ?? dayOfWeek}${formatClock(startTime)}~${formatClock(endTime)}`
}

export function formatSchedule(times: readonly OfferingTimeDto[]): string {
  return times.map(formatTime).join(', ')
}
