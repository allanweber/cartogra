// Production entry: the TanStack Start build exports a fetch handler but does not listen.
import { serve } from 'srvx'
import { staticMiddleware } from 'srvx/static'

import app from './dist/server/server.js'

serve({
  port: Number(process.env.PORT ?? 3000),
  hostname: '0.0.0.0',
  middleware: [staticMiddleware({ dir: 'dist/client' })],
  fetch: app.fetch,
})
