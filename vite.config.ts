import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * The API is a separate Spring Boot process (`backend/`, port 8080). Dev and
 * preview both proxy `/api/*` to it, so the browser still sees ONE origin and
 * there is no CORS — ADR-002 replaced the old in-process mount
 * (`server/vite-plugin.ts`, retired at the Phase 1 parity gate).
 *
 * `scripts/dev.mjs` runs both processes together: `npm run dev`.
 */
const apiProxy = {
  '/api': { target: 'http://localhost:8080' },
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: false,
    open: false,
    proxy: apiProxy,
  },
  preview: {
    port: 4173,
    proxy: apiProxy,
  },
})
