import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Generate build timestamp in JST
const buildTime = new Date().toLocaleString('ja-JP', {
  timeZone: 'Asia/Tokyo',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit'
}).replace(/\//g, '-')

export default defineConfig({
  plugins: [react()],
  base: '/core/ui/',
  define: {
    __UI_BUILD_TIME__: JSON.stringify(buildTime),
    __UI_VERSION__: JSON.stringify('3.0.0')
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true
  },
  server: {
    proxy: {
      '^/core/(?!ui).*': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
