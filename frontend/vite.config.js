/* global process */
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Inside Docker the backend is reachable as http://backend:8080 (compose sets
// VITE_PROXY_TARGET). On a bare `npm run dev` the container name doesn't resolve,
// so default to localhost where docker-compose publishes port 8080.
const proxyTarget = process.env.VITE_PROXY_TARGET || 'http://localhost:8080'

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
      '/api': {
        target: proxyTarget,
        changeOrigin: true,
        secure: false,
      }
    }
  }
})