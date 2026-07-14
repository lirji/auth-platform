import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// dev :5273 / 同源反代 /admin -> auth-platform-admin :8201 (免 CORS)。
// Casdoor(:8000) 不代理:authority 直连,保持 issuer 一致。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  return {
    plugins: [react()],
    server: {
      port: 5273,
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
