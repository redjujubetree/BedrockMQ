import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: '/bedrockmq-admin/',
  build: {
    outDir: 'target/classes/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/bedrockmq-admin/bedrock/': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
