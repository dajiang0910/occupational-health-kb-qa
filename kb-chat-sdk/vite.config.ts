import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    lib: {
      entry: 'src/index.ts',
      name: 'OhkbChatSDK',
      formats: ['iife'],
      fileName: () => 'ohkb-chat-sdk.js',
    },
    outDir: 'dist',
    cssCodeSplit: false,
  },
  server: {
    port: 5173,
  },
});
