import { Role } from '../gen/server_client_pb'

export type UserRole = typeof Role.STUDENT | typeof Role.PROFESSOR

export const roleLabels: Record<UserRole, string> = {
  [Role.STUDENT]: '학생',
  [Role.PROFESSOR]: '교수',
}

export function isUserRole(role: unknown): role is UserRole {
  return role === Role.STUDENT || role === Role.PROFESSOR
}
