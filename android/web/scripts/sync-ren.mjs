// Copies ren desktop's files (the import closure of the entries below, type imports included) into android/web/src/ren
// and src/data, unmodified, and writes their SHA256SUMS. Usage: node scripts/sync-ren.mjs <ren desktop checkout> .
import { createHash } from "node:crypto"
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs"
import path from "node:path"

const [desktop, web] = process.argv.slice(2)
const entries = [
  "src/pages/SearchResultsPage.tsx", "src/pages/HomePage.tsx", "src/pages/OnboardingPage.tsx", "src/pages/SignInPage.tsx",
  "src/pages/DocumentsPage.tsx", "src/pages/ProjectsIndexPage.tsx", "src/pages/ProjectHomePage.tsx", "src/pages/DeepResearchPage.tsx",
  "src/layouts/AuthPageLayout.tsx", "src/components/interests/InterestsWidget.tsx", "src/components/interests/ContextBridge.tsx", "src/components/navigation/destinations.ts",
  "src/components/ai-elements/conversation.tsx", "src/components/ai-elements/message.tsx", "src/components/ai-elements/response.tsx",
  "src/components/ai-elements/chain-of-thought.tsx", "src/components/ai-elements/tool.tsx", "src/components/ai-elements/shimmer.tsx",
  "src/components/chrome/UrlBar.tsx", "src/context/AgentUiContext.tsx", "src/lib/api-base.ts", "src/lib/project-documents.ts",
  "src/lib/search-api.ts", "src/lib/sign-in.ts", "src/stores/authStore.ts", "src/stores/favoritesStore.ts",
  "src/stores/navigationStore.ts", "src/stores/tabStore.ts", "src/stores/onboardingStore.ts", "src/stores/contextAgentStore.ts",
  "src/stores/interestsStore.ts", "src/stores/accountProfileStore.ts", "src/utils/cn.ts", "src/index.css", "src/vite-env.d.ts", "src/pages/types/electron.ts",
]
const extensions = ["", ".ts", ".tsx", ".d.ts", "/index.ts", "/index.tsx"]

function resolve(from, spec) {
  let base
  if (spec.startsWith("@/")) base = path.join(desktop, "src", spec.slice(2))
  else if (spec.startsWith("@catalyst/")) base = path.join(desktop, "src/components/catalyst", spec.slice(10))
  else if (spec.startsWith(".")) base = path.resolve(path.dirname(from), spec)
  else return null
  base = base.replace(/\?.*$/, "")
  for (const ext of extensions) {
    const file = base + ext
    if (existsSync(file) && statSync(file).isFile()) return file
  }
  throw new Error(`${path.relative(desktop, from)}: cannot resolve ${spec}`)
}

const seen = new Set()
const queue = entries.map((entry) => path.join(desktop, entry))
while (queue.length) {
  const file = queue.pop()
  if (seen.has(file)) continue
  seen.add(file)
  if (!/\.(tsx?|css)$/.test(file)) continue
  const text = readFileSync(file, "utf8")
  const pattern = /\b(?:from|import)\s*\(?\s*['"]([^'"]+)['"]|@import\s+['"]([^'"]+)['"]/g
  for (const match of text.matchAll(pattern)) {
    const spec = match[1] ?? match[2]
    const target = resolve(file, spec)
    if (target) queue.push(target)
  }
}

const destination = (file) => {
  const relative = path.relative(desktop, file)
  if (relative.startsWith("src/")) return ["ren", relative.slice(4)]
  if (relative.startsWith("data/")) return ["data", relative.slice(5)]
  throw new Error(`outside src and data: ${relative}`)
}

const files = { ren: [], data: [] }
for (const file of seen) files[destination(file)[0]].push(file)
files.ren.push(path.join(desktop, "tailwind.config.ts"))

for (const folder of ["ren", "data"]) {
  const root = path.join(web, "src", folder)
  const keep = new Set(["SOURCE.txt"])
  for (const name of readFileSync(path.join(root, "SHA256SUMS"), "utf8").trim().split("\n").map((line) => line.split(/\s+/)[1])) {
    if (!keep.has(name)) rmSync(path.join(root, name), { force: true })
  }
  const sums = []
  for (const file of files[folder]) {
    const name = file.endsWith("tailwind.config.ts") && path.dirname(file) === desktop ? "tailwind.config.ts" : destination(file)[1]
    const target = path.join(root, name)
    mkdirSync(path.dirname(target), { recursive: true })
    copyFileSync(file, target)
    sums.push(`${createHash("sha256").update(readFileSync(target)).digest("hex")}  ${name}`)
  }
  sums.sort((a, b) => a.split("  ")[1].localeCompare(b.split("  ")[1]))
  writeFileSync(path.join(root, "SHA256SUMS"), sums.join("\n") + "\n")
  console.log(`${folder}: ${sums.length} files`)
}
