import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

// Kud Keys web app. Built into server/public and served by the Hono server at /.
// Dev runs on its own port and proxies /api to the server, so Better Auth
// cookies work without CORS.
export default defineConfig(({ command, mode }) => ({
  base: '/',
  // Dev only (conductor worktrees): the signed-out app sends the browser to the
  // server's auto sign-in when the root .env sets KUDKEYS_DEV_AUTO_SIGNIN
  // (server/dev-auto-sign-in.ts). Always false in a build.
  define: {
    __DEV_AUTO_SIGNIN__: JSON.stringify(
      command === 'serve' && Boolean(loadEnv(mode, resolve(__dirname, '..'), 'KUDKEYS_DEV_').KUDKEYS_DEV_AUTO_SIGNIN),
    ),
  },
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': resolve(__dirname, './src') },
  },
  build: {
    outDir: '../server/public',
    emptyOutDir: true,
  },
  server: {
    port: Number(process.env.PORT) || 5181,
    strictPort: true,
    // Dev servers run on hammas-dev and are opened over Tailscale.
    allowedHosts: ['.ts.net', 'localhost', '127.0.0.1'],
    proxy: {
      '/api': { target: `http://localhost:${process.env.API_PORT || 8080}`, changeOrigin: true },
    },
  },
}))
