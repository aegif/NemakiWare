import { defineConfig, Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import * as fs from 'fs'
import * as path from 'path'

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

/**
 * Vite plugin to update OIDC/SAML callback HTML files with correct asset references
 * This ensures the callback pages always reference the current build's hashed assets
 */
function updateCallbackHtmlPlugin(): Plugin {
  return {
    name: 'update-callback-html',
    writeBundle(options, bundle) {
      const outDir = options.dir || 'dist'
      const indexHtmlPath = path.join(outDir, 'index.html')

      // Read the generated index.html to extract asset references
      if (!fs.existsSync(indexHtmlPath)) {
        console.warn('[update-callback-html] index.html not found, skipping callback update')
        return
      }

      const indexHtml = fs.readFileSync(indexHtmlPath, 'utf-8')

      // Extract JS and CSS asset references from index.html
      const jsMatch = indexHtml.match(/src="(\/core\/ui\/assets\/index-[^"]+\.js)"/)
      const cssMatch = indexHtml.match(/href="(\/core\/ui\/assets\/index-[^"]+\.css)"/)

      if (!jsMatch || !cssMatch) {
        console.warn('[update-callback-html] Could not extract asset references from index.html')
        return
      }

      const jsAsset = jsMatch[1]
      const cssAsset = cssMatch[1]

      // Update callback HTML files in dist directory
      const callbackFiles = ['oidc-callback.html', 'saml-callback.html']

      for (const fileName of callbackFiles) {
        const filePath = path.join(outDir, fileName)
        if (fs.existsSync(filePath)) {
          let content = fs.readFileSync(filePath, 'utf-8')

          // Update JS asset reference
          content = content.replace(
            /src="\/core\/ui\/assets\/index-[^"]+\.js"/,
            `src="${jsAsset}"`
          )

          // Update CSS asset reference
          content = content.replace(
            /href="\/core\/ui\/assets\/index-[^"]+\.css"/,
            `href="${cssAsset}"`
          )

          fs.writeFileSync(filePath, content, 'utf-8')
          console.log(`[update-callback-html] Updated ${fileName} with assets: ${jsAsset}`)
        }
      }

      // Also update source files in public/ directory for consistency
      const publicDir = path.join(process.cwd(), 'public')
      for (const fileName of callbackFiles) {
        const publicFilePath = path.join(publicDir, fileName)
        if (fs.existsSync(publicFilePath)) {
          let content = fs.readFileSync(publicFilePath, 'utf-8')

          content = content.replace(
            /src="\/core\/ui\/assets\/index-[^"]+\.js"/,
            `src="${jsAsset}"`
          )

          content = content.replace(
            /href="\/core\/ui\/assets\/index-[^"]+\.css"/,
            `href="${cssAsset}"`
          )

          fs.writeFileSync(publicFilePath, content, 'utf-8')
          console.log(`[update-callback-html] Updated public/${fileName} source file`)
        }
      }
    }
  }
}

export default defineConfig({
  plugins: [react(), updateCallbackHtmlPlugin()],
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
