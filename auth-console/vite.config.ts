import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import type { Plugin } from 'vite'

// 门户跨域探测专用健康端点；不走 SPA 回退。
function healthzPlugin(): Plugin {
  const respond = (res: { setHeader(name: string, value: string): void; end(body: string): void }) => {
    res.setHeader('Access-Control-Allow-Origin', '*')
    res.setHeader('Cache-Control', 'no-store')
    res.setHeader('Content-Type', 'text/plain; charset=utf-8')
    res.end('ok\n')
  }
  return {
    name: 'auth-console-healthz',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== '/healthz') return next()
        respond(res)
      })
    },
    configurePreviewServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== '/healthz') return next()
        respond(res)
      })
    },
  }
}

// 本地入口端口来自中央注册表 AUTH_CONSOLE_UI_PORT；缺省 5273。
// 同源反代 /admin -> auth-platform-admin :8201 (免 CORS)。Casdoor(:8000) 不代理。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  return {
    plugins: [react(), healthzPlugin()],
    server: {
      host: true,
      port: Number(process.env.AUTH_CONSOLE_UI_PORT || 5273),
      proxy: {
        '/admin': {
          target: env.VITE_ADMIN_TARGET || 'http://localhost:8201',
          changeOrigin: true,
        },
      },
    },
    build: {
      chunkSizeWarningLimit: 1200,
      rollupOptions: {
        output: {
          manualChunks: {
            react: ['react', 'react-dom', 'react-router-dom'],
            antd: ['antd', '@ant-design/icons'],
            oidc: ['oidc-client-ts', 'react-oidc-context'],
            query: ['@tanstack/react-query'],
          },
        },
      },
    },
  }
})
