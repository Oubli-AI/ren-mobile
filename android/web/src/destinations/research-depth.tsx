import { DEPTH_METADATA } from "@/lib/deep-research-api"
import { useDeepResearchStore } from "@/stores/deepResearchStore"
import { cn } from "@/utils/cn"

/** Squad's depth, kept in ren's store as the hero's own control keeps it, drawn as the picker's reasoning row is. */
export function ResearchDepth() {
  const depths = useDeepResearchStore((state) => state.depths)
  const depth = useDeepResearchStore((state) => state.preferredDepth)
  const choose = useDeepResearchStore((state) => state.setPreferredDepth)
  return (
    <div role="radiogroup" aria-label="Research depth"
      className="flex shrink-0 flex-wrap items-center gap-1 border-t border-surface-200 px-2 py-2 dark:border-surface-700">
      <span className="mr-auto px-1.5 text-[10px] font-semibold uppercase tracking-wider text-ink-muted dark:text-ink-inverse-muted">Research depth</span>
      {DEPTH_METADATA.filter((item) => depths.includes(item.id)).map((item) => (
        <button type="button" key={item.id} role="radio" aria-checked={depth === item.id} title={item.description}
          onClick={() => choose(item.id)}
          className={cn("rounded-md px-2 py-1 text-[11px] font-medium transition-colors hover:bg-surface-100 dark:hover:bg-surface-800",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:focus-visible:ring-offset-surface-900",
            depth === item.id && "bg-indigo-600 text-white hover:bg-indigo-600 dark:hover:bg-indigo-600")}>
          {item.label}
        </button>
      ))}
    </div>
  )
}
