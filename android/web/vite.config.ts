import path from "node:path"
import react from "@vitejs/plugin-react"
import autoprefixer from "autoprefixer"
import tailwindcss from "tailwindcss"
import { defineConfig, type Plugin } from "vite"

// What the pages carry that a phone never loads stays out of the APK, which a sideload keeps under 20 MB.
// Univer's document layout (the spreadsheet's cells are laid out by it) loads a hyphenation pattern for each of 77
// languages beside the English it holds, about 1 MB compressed, and only for a document with automatic hyphenation
// on, which a sheet's cells never have: each of those imports gets an empty module, so asking for one hyphenates
// nothing.
const withoutHyphenationPatterns: Plugin = {
  name: "ren:without-hyphenation-patterns",
  enforce: "pre",
  resolveId: (source, importer) =>
    importer?.includes("/@univerjs/engine-render/lib/es/doc-skeleton-") && source.startsWith("./") ? "\0no-hyphenation-pattern" : null,
  load: (id) => (id === "\0no-hyphenation-pattern" ? "export default []" : null),
}
// KaTeX's stylesheet lists each font as WOFF2, WOFF and TrueType; a WebView takes the first, so only that one is kept.
const katexWoff2: Plugin = {
  name: "ren:katex-woff2",
  enforce: "pre",
  transform: (code, id) =>
    /\/katex\/dist\/katex\.min\.css(\?|$)/.test(id) ? code.replace(/,url\(fonts\/[\w-]+\.(?:woff|ttf)\) format\("(?:woff|truetype)"\)/g, "") : null,
}

// Ren's pages for the app's WebViews. Each is its own page under assets/ren/, loaded through WebViewAssetLoader, with
// its chunks beside it (the robot's bloom pass loads only on wide screens). The search page, ren desktop's destinations and the agent panel's chat
// are ren desktop's own pages and components (src/ren, which its files import as "@"), styled by its Tailwind config.
// Vibe's studio (src/vibe) draws with ElevenLabs UI's components. A spreadsheet's engine (src/ren/components/grid/engine.ts,
// Univer) is a chunk of its own, loaded when a spreadsheet first calculates or opens.
// https://vite.dev/config/
export default defineConfig({
  root: path.resolve(__dirname, "src"),
  envDir: __dirname,
  base: "./",
  plugins: [withoutHyphenationPatterns, katexWoff2, react()],
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
