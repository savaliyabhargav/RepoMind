import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    // Port 5173 is mapped to your host, so you can see it in the browser
    port: 5173,
    host: true, // Crucial for Docker to allow external access
    proxy: {
      '/api': {
        // CHANGE: Use the container name 'backend' instead of localhost
        target: 'http://backend:8080', 
        changeOrigin: true,
        secure: false,
      }
    }
  }
})