# NemakiWare React UI

## Setup
- Node.js 18+
- Install deps:
  npm ci

## Dev
- Backend at http://localhost:8080
- Optional env:
  VITE_API_BASE=/core
  VITE_PROXY_TARGET=http://localhost:8080
- Start:
  npm run dev
- App base:
  http://localhost:5173/core/ui/

## Build
npm run build

## Type check
npm run type-check

## E2E (Playwright)
- Install browsers: npx playwright install
- Run tests: npm run test:e2e
- Headed: npm run test:e2e:headed
