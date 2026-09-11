import vue from '@vitejs/plugin-vue'
import { defineConfig, type UserConfig } from 'vite'

// 开发期 Vite(:5173) 代理后端(:8080)，解决 CORS。
// 前端路由用 hash 模式（createWebHashHistory）——hash 不发往服务端，
// 故 SPA 导航路径与后端 API 路径（/chat /admin/* /eval/run /api/obs/summary）零冲突，
// dev 代理与生产单 jar 静态托管皆无需 SPA fallback 兜底。
//
// 注：vitest 3.x 内置的 vite 与顶层 Vite 8(rolldown) 类型不同源，
// 故 test 字段经变量类型 UserConfig & {test?} 透传（避开字面量超额属性检查），
// 运行时由 vitest 直接读取。
const config: UserConfig & { test?: Record<string, unknown> } = {
  plugins: [vue()],
  // 生产构建直出后端 classpath:/static/（单 jar 部署：mvn package 打入 jar，
  // 后端 @Controller + 静态资源同源服务；hash 路由故无需 SPA fallback）。
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/chat': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE (text/event-stream) 不可缓冲：关代理压缩、保流式
        selfHandleResponse: false,
      },
      '/admin': 'http://localhost:8080',
      '/eval': 'http://localhost:8080',
      '/api': 'http://localhost:8080',
      '/health': 'http://localhost:8080',
      '/info': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.ts'],
  },
}

export default defineConfig(config)
