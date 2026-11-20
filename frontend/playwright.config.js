import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e', timeout: 30000, workers: 1, retries: 0,
  use: { baseURL: process.env.TV3_E2E_BASE_URL || 'http://127.0.0.1:5173', channel: process.env.TV3_E2E_CHANNEL || 'chrome', headless: true, timezoneId: 'Asia/Bangkok', screenshot: 'only-on-failure' },
});
