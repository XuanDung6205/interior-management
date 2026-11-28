import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:8080' } },
  test: { maxWorkers: 1, testTimeout: 30000, hookTimeout: 30000, include: ['src/**/*.test.{js,jsx}'], environment: 'jsdom', setupFiles: ['./src/test/setup.js'], clearMocks: true },
});
