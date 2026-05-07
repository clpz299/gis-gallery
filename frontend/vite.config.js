import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import cesium from 'vite-plugin-cesium'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue(), cesium()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // rewrite: (path) => path.replace(/^\/api/, '') 
        // 根据后端设计，如果后端接口本身包含 /api 前缀，则不需要 rewrite
        // 这里后端是 /api/gridtile/... 所以不需要 rewrite
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true
  }
})
