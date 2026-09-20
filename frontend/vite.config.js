/* global process */
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The backend used to be one process behind '/api'. It's now 4 independent
// services, each reachable at the root of its own host:port (no /api prefix
// on their side) — so every proxy rule below strips '/api' before forwarding
// and picks a different target. Inside Docker each target resolves by its
// compose service name; on a bare `npm run dev` these default to localhost
// where docker-compose publishes the matching port.
const authServiceTarget = process.env.VITE_AUTH_SERVICE_TARGET || 'http://localhost:8081'
const ingestionServiceTarget = process.env.VITE_INGESTION_SERVICE_TARGET || 'http://localhost:8082'
const explainServiceTarget = process.env.VITE_EXPLAIN_SERVICE_TARGET || 'http://localhost:8083'
const analysisServiceTarget = process.env.VITE_ANALYSIS_SERVICE_TARGET || 'http://localhost:8084'

const stripApiPrefix = (path) => path.replace(/^\/api/, '')

export default defineConfig({
  plugins: [react()],
  server: {
    // Port 5173 is mapped to your host, so you can see it in the browser
    port: 5173,
    host: true, // Crucial for Docker to allow external access
    // Allow ngrok (and any) host headers — without this Vite returns
    // "Blocked request. This host is not allowed" when accessed via ngrok.
    allowedHosts: true,
    proxy: {
      // Most specific first — these two /repo/... shapes belong to
      // explain-diagram-service, not repo-ingestion-service, so they must be
      // matched before the general '/api/repo' rule below.
      '^/api/repo/[^/]+/files/[^/]+/explain$': {
        target: explainServiceTarget,
        changeOrigin: true,
        secure: false,
        rewrite: stripApiPrefix,
      },
      '^/api/repo/[^/]+/overview$': {
        target: explainServiceTarget,
        changeOrigin: true,
        secure: false,
        rewrite: stripApiPrefix,
      },
      '/api/repo': {
        target: ingestionServiceTarget,
        changeOrigin: true,
        secure: false,
        rewrite: stripApiPrefix,
      },
      '/api/analyses': {
        target: analysisServiceTarget,
        changeOrigin: true,
        secure: false,
        rewrite: stripApiPrefix,
      },
      '/api/auth': {
        target: authServiceTarget,
        changeOrigin: true,
        secure: false,
        rewrite: stripApiPrefix,
      },
    }
  }
})
