import { createClient } from '@connectrpc/connect'
import { ServerClientService } from '../gen/server_client_pb'
import { transport } from './transport'

export const api = createClient(ServerClientService, transport)
