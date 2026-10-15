import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:18080', changeOrigin: true },
      '/demand': { target: 'http://127.0.0.1:18082', changeOrigin: true, rewrite: path => path.replace(/^\/demand/, '') },
      '/provider': { target: 'http://127.0.0.1:18081', changeOrigin: true, rewrite: path => path.replace(/^\/provider/, '') },
    },
  },
})
