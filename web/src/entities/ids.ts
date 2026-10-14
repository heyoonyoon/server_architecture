export function splitIds(input: string): string[] {
  return input.split(/[\s,]+/).filter(Boolean)
}
