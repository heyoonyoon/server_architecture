import { ConnectError, createClient } from '@connectrpc/connect'
import { createGrpcWebTransport } from '@connectrpc/connect-web'
import { ServerClientService } from './gen/server_client_pb'

export const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

const transport = createGrpcWebTransport({ baseUrl, defaultTimeoutMs: 5000 })

export const api = createClient(ServerClientService, transport)

export function errorMessage(error: unknown): string {
  return ConnectError.from(error).message
}

export function splitIds(input: string): string[] {
  return input.split(/[\s,]+/).filter(Boolean)
}
