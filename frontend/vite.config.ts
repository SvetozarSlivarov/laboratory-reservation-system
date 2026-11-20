import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/oauth2/authorization': 'http://127.0.0.1:8080',
      '/login/oauth2/code': 'http://127.0.0.1:8080',
      '/actuator/health': 'http://127.0.0.1:8080',
    },
  },
});
