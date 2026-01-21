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
 * Loading indicator styles for SSO callback pages
 * These are shown while React initializes
 */
const callbackLoadingStyles = `
    <style>
      .sso-loading {
        display: flex;
        flex-direction: column;
        justify-content: center;
        align-items: center;
        min-height: 100vh;
        background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
      }
      .sso-loading h2 { color: #333; margin-bottom: 16px; }
      .sso-loading p { color: #666; }
      .sso-spinner {
        border: 4px solid #f3f3f3;
        border-top: 4px solid #1890ff;
        border-radius: 50%;
        width: 40px;
        height: 40px;
        animation: sso-spin 1s linear infinite;
        margin: 20px 0;
      }
      @keyframes sso-spin {
        0% { transform: rotate(0deg); }
        100% { transform: rotate(360deg); }
      }
      #root:not(:empty) + .sso-loading { display: none; }
    </style>`

/**
 * Vite plugin to generate OIDC/SAML callback HTML files from index.html
 *
 * This solves the asset hash mismatch problem by:
 * 1. NOT storing callback files in public/ (no git diff on each build)
 * 2. Generating callback files from index.html in dist/ during build
 * 3. Ensuring all HTML files always have matching asset references
 */
function generateCallbackHtmlPlugin(): Plugin {
  return {
    name: 'generate-callback-html',
    writeBundle(options, bundle) {
      const outDir = options.dir || 'dist'
      const indexHtmlPath = path.join(outDir, 'index.html')

      if (!fs.existsSync(indexHtmlPath)) {
        console.warn('[generate-callback-html] index.html not found, skipping callback generation')
        return
      }

      const indexHtml = fs.readFileSync(indexHtmlPath, 'utf-8')

      // Callback page configurations
      const callbackPages = [
        {
          fileName: 'oidc-callback.html',
          title: 'OIDC Callback - NemakiWare',
          loadingTitle: 'OIDC認証処理中...',
          loadingMessage: '認証が完了するまでお待ちください。'
        },
        {
          fileName: 'saml-callback.html',
          title: 'SAML Callback - NemakiWare',
          loadingTitle: 'SAML認証処理中...',
          loadingMessage: '認証が完了するまでお待ちください。'
        }
      ]

      for (const page of callbackPages) {
        // Start with index.html as base
        let callbackHtml = indexHtml

        // Update title
        callbackHtml = callbackHtml.replace(
          /<title>[^<]+<\/title>/,
          `<title>${page.title}</title>`
        )

        // Add loading styles before </head>
        callbackHtml = callbackHtml.replace(
          '</head>',
          `${callbackLoadingStyles}\n  </head>`
        )

        // Add loading indicator after <div id="root"></div>
        const loadingIndicator = `
    <!-- Loading indicator shown until React app mounts -->
    <div class="sso-loading">
      <h2>${page.loadingTitle}</h2>
      <div class="sso-spinner"></div>
      <p>${page.loadingMessage}</p>
    </div>`

        callbackHtml = callbackHtml.replace(
          '<div id="root"></div>',
          `<div id="root"></div>${loadingIndicator}`
        )

        // Write the generated callback file
        const filePath = path.join(outDir, page.fileName)
        fs.writeFileSync(filePath, callbackHtml, 'utf-8')
        console.log(`[generate-callback-html] Generated ${page.fileName}`)
      }
    }
  }
}

export default defineConfig({
  plugins: [react(), generateCallbackHtmlPlugin()],
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
