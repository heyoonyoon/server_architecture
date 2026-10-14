import type { Role } from '../api/dto'

export type UserRole = Exclude<Role, 'ROLE_UNSPECIFIED'>

export const roleLabels: Record<UserRole, string> = {
  STUDENT: '학생',
  PROFESSOR: '교수',
}

export function isUserRole(role: unknown): role is UserRole {
  return role === 'STUDENT' || role === 'PROFESSOR'
}
