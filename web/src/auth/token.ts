function decodeSegment(segment: string): unknown {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/')
  const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')
  return JSON.parse(atob(padded))
}

export function tokenExpiresAt(token: string): number | null {
  try {
    const payload = decodeSegment(token.split('.')[1] ?? '')
    if (typeof payload !== 'object' || payload === null) return null
    const { exp } = payload as Record<string, unknown>
    return typeof exp === 'number' ? exp * 1000 : null
  } catch {
    return null
  }
}
