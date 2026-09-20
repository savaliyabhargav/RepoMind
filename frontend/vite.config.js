/* global process */
import http from 'node:http'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The backend used to be one process behind '/api'. It's now 4 independent
// services, each reachable at the root of its own host:port (no /api prefix
// on their side). Vite's built-in server.proxy shorthand (regex keys, and
// the `router` option) did not behave as documented in this Vite version —
// requests kept landing on the wrong service regardless of key order — so
// routing is done here with a small self-written middleware using only
// Node's built-in `http` module. Fully deterministic, easy to test in
// isolation. Inside Docker each target resolves by its compose service
// name; on a bare `npm run dev` these default to localhost where
// docker-compose publishes the matching port.
const authServiceTarget = process.env.VITE_AUTH_SERVICE_TARGET || 'http://localhost:8081'
const ingestionServiceTarget = process.env.VITE_INGESTION_SERVICE_TARGET || 'http://localhost:8082'
const explainServiceTarget = process.env.VITE_EXPLAIN_SERVICE_TARGET || 'http://localhost:8083'
const analysisServiceTarget = process.env.VITE_ANALYSIS_SERVICE_TARGET || 'http://localhost:8084'

const explainPathPattern = /^\/api\/repo\/[^/]+\/files\/[^/]+\/explain(?:$|\?)/
const overviewPathPattern = /^\/api\/repo\/[^/]+\/overview(?:$|\?)/

function pickTarget(requestUrl) {
  if (explainPathPattern.test(requestUrl) || overviewPathPattern.test(requestUrl)) {
    return explainServiceTarget
  }
  if (requestUrl.startsWith('/api/repo')) {
    return ingestionServiceTarget
  }
  if (requestUrl.startsWith('/api/analyses')) {
    return analysisServiceTarget
  }
  if (requestUrl.startsWith('/api/auth')) {
    return authServiceTarget
  }
  return null
}

function apiRouterPlugin() {
  return {
    name: 'repomind-api-router',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (!req.url || !req.url.startsWith('/api')) {
          next()
          return
        }
        const target = pickTarget(req.url)
        if (!target) {
          next()
          return
        }
        const targetUrl = new URL(target)
        const forwardedPath = req.url.replace(/^\/api/, '') || '/'
        const proxyReq = http.request(
          {
            hostname: targetUrl.hostname,
            port: targetUrl.port,
            path: forwardedPath,
            method: req.method,
            headers: { ...req.headers, host: targetUrl.host },
          },
          (proxyRes) => {
            res.writeHead(proxyRes.statusCode || 502, proxyRes.headers)
            proxyRes.pipe(res)
          }
        )
        proxyReq.on('error', (err) => {
          if (!res.headersSent) {
            res.writeHead(502, { 'Content-Type': 'application/json' })
          }
          res.end(JSON.stringify({ error: `Proxy error reaching ${target}: ${err.message}` }))
        })
        req.pipe(proxyReq)
      })
    },
  }
}

export default defineConfig({
  plugins: [react(), apiRouterPlugin()],
  server: {
    // Port 5173 is mapped to your host, so you can see it in the browser
    port: 5173,
    host: true, // Crucial for Docker to allow external access
    // Allow ngrok (and any) host headers — without this Vite returns
    // "Blocked request. This host is not allowed" when accessed via ngrok.
    allowedHosts: true,
  }
})
