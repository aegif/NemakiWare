import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: '/core/ui/',
  build: {
    outDir: 'dist',
    emptyOutDir: true
  },
  server: {
    proxy: {
      '/rest': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
