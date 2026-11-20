import path from "node:path"
import react from "@vitejs/plugin-react"
import autoprefixer from "autoprefixer"
import tailwindcss from "tailwindcss"
import { defineConfig } from "vite"

// Ren's pages for the app's WebViews. Each is its own page under assets/ren/, loaded through WebViewAssetLoader, with
// its chunks beside it (the robot's bloom pass loads only on wide screens). The search page, ren desktop's destinations and the agent panel's chat
// are ren desktop's own pages and components (src/ren, which its files import as "@"), styled by its Tailwind config.
// Vibe's studio (src/vibe) draws with ElevenLabs UI's components.
// https://vite.dev/config/
export default defineConfig({
  root: path.resolve(__dirname, "src"),
  envDir: __dirname,
  base: "./",
  plugins: [react()],
  publicDir: false,
  // ElevenLabs UI's components (src/elevenlabs) import each other at the paths shadcn installs them to.
  resolve: {
    alias: [
      { find: /^@\/components\/ui\/(orb|audio-player|waveform|voice-picker)$/, replacement: path.resolve(__dirname, "src/elevenlabs/components/ui/$1") },
      // ren's model picker gets the phone's popover (src/destinations/picker.tsx), which can add Research Depth under it.
      {
        find: "@/components/ui/popover",
        replacement: path.resolve(__dirname, "src/ren/components/ui/popover.tsx"),
        customResolver: (source, importer) =>
          importer?.endsWith("/components/agent-panel/ModelSelector.tsx") ? path.resolve(__dirname, "src/destinations/picker.tsx") : source,
      },
      { find: "@catalyst", replacement: path.resolve(__dirname, "src/ren/components/catalyst") },
      { find: "@", replacement: path.resolve(__dirname, "src/ren") },
    ],
  },
  css: { postcss: { plugins: [tailwindcss({ config: path.resolve(__dirname, "tailwind.config.ts") }), autoprefixer()] } },
  build: {
    outDir: path.resolve(__dirname, "../app/src/main/assets/ren/web"),
    emptyOutDir: true,
    target: "es2022",
    sourcemap: false,
    rollupOptions: {
      input: {
        robot: path.resolve(__dirname, "src/robot/index.html"),
        search: path.resolve(__dirname, "src/search/index.html"),
        destinations: path.resolve(__dirname, "src/destinations/index.html"),
        panel: path.resolve(__dirname, "src/panel/index.html"),
        context: path.resolve(__dirname, "src/context/index.html"),
      },
    },
  },
})
