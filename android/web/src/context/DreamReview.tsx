import { useEffect, useMemo, useState } from "react"
import { getSession } from "@/api/supabase"
import { field, ghostButton, mutedText, primaryButton } from "@/components/interests/contextUi"
import { API_BASE_URL } from "@/lib/api-base"
import { dreamTopicLabel, type Dream, type TopicReview } from "@/lib/context-api"
import { createMemoryRequest, memoryDreams } from "@/lib/memory-api"
import { useContextAgentStore } from "@/stores/contextAgentStore"
import { cn } from "@/utils/cn"

/** A topic's text as lines: what changed is marked, what stayed reads muted. */
function Lines({ text, other, tone }: { text: string; other: Set<string>; tone: "added" | "removed" }) {
  return (
    <pre className="whitespace-pre-wrap break-words font-sans text-[12px] leading-relaxed">
      {text.split("\n").map((line, index) => (
        <span key={index} className={cn("block", other.has(line)
          ? "text-ink-secondary dark:text-ink-inverse-secondary"
          : tone === "added"
            ? "bg-success/15 text-ink dark:text-ink-inverse"
            : "bg-danger/15 text-ink line-through dark:text-ink-inverse")}>
          {line || " "}
        </span>
      ))}
    </pre>
  )
}

/**
 * The dream's review on the phone, over the whole view: every topic the dream changed, with what it read before and
 * what it reads now (a new topic's whole text, a removed topic's whole text), the lines that changed marked. The
 * checklist and comments go to the dreaming agent as ren desktop's DreamNotice sends them (`reviewDream`). The
 * versions' earlier texts are ren's own (`/memory/dreams`, as its Memory panel reads them).
 */
export function DreamReview({ dream, userId }: { dream: Dream; userId: string }) {
  const { setReviewingDream, reviewDream } = useContextAgentStore.getState()
  const [review, setReview] = useState<TopicReview[]>(() => dream.topics.map(({ key }) => ({ key, right: true, comment: "" })))
  const [before, setBefore] = useState<Map<string, string | null> | null>(null)
  const [error, setError] = useState("")
  const request = useMemo(() => createMemoryRequest(API_BASE_URL, getSession, userId), [userId])
  useEffect(() => {
    memoryDreams(request).then(({ dreams }) => {
      const version = dreams.find((entry) => entry.version === dream.version)
      setBefore(new Map((version?.changed ?? []).map((topic) => [topic.key, topic.before])))
    }, (cause: unknown) => setError(cause instanceof Error ? cause.message : "What the topics read before didn't load."))
  }, [request, dream.version])
  const mark = (index: number, patch: Partial<TopicReview>) =>
    setReview(review.map((entry, at) => at === index ? { ...entry, ...patch } : entry))

  return (
    <div data-testid="dream-review" className="fixed inset-3 z-50 flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-3 text-ink shadow-float dark:border-surface-800 dark:bg-surface-900 dark:text-ink-inverse">
      <p className="text-[13px] font-semibold">Check what Ren remembered</p>
      <p className={cn("mt-1", mutedText)}>
        Each topic shows what it read before and what it reads now. Uncheck anything wrong and say why. Ren fixes it in
        its next dream and keeps your notes for every dream after.
      </p>
      {error && <p className="mt-1 text-[12px] text-danger">{error}</p>}
      <ul className="mt-2 min-h-0 flex-1 space-y-2 overflow-y-auto">
        {dream.topics.map((topic, index) => {
          const entry = review[index]
          if (!entry) return null
          const label = dreamTopicLabel(topic)
          const was = before?.get(topic.key) ?? null
          const now = topic.text
          const kind = now === null ? "Removed" : before === null ? "" : was === null ? "New" : "Changed"
          const wasLines = new Set(was?.split("\n") ?? [])
          const nowLines = new Set(now?.split("\n") ?? [])
          return (
            <li key={topic.key} className="rounded-lg border border-surface-200 p-2.5 dark:border-surface-800">
              <label className="flex items-start gap-2">
                <input type="checkbox" checked={entry.right} onChange={(event) => mark(index, { right: event.target.checked })}
                  aria-label={`${label.title} is right`} className="mt-0.5 h-4 w-4 flex-shrink-0 accent-blurple-600 dark:accent-blurple-400" />
                <span className="min-w-0 flex-1 text-[13px] font-semibold">{label.title}
                  <span className="font-normal text-ink-muted dark:text-ink-inverse-muted"> · {label.agent}{kind && ` · ${kind}`}</span>
                </span>
              </label>
              {was !== null && <>
                <p className="mt-2 text-[11px] font-semibold uppercase tracking-wide text-ink-muted dark:text-ink-inverse-muted">Before</p>
                <Lines text={was} other={nowLines} tone="removed" />
              </>}
              {now !== null && <>
                <p className="mt-2 text-[11px] font-semibold uppercase tracking-wide text-ink-muted dark:text-ink-inverse-muted">{was === null ? "Text" : "Now"}</p>
                <Lines text={now} other={wasLines} tone="added" />
              </>}
              <input type="text" value={entry.comment} onChange={(event) => mark(index, { comment: event.target.value })}
                maxLength={2000} placeholder="Comment" aria-label={`Comment on ${label.title}`} className={cn(field, "mt-2")} />
            </li>
          )
        })}
      </ul>
      <div className="mt-2.5 flex items-center justify-end gap-2">
        <button type="button" onClick={() => setReviewingDream(false)} className={ghostButton}>Later</button>
        <button type="button" onClick={() => void reviewDream(review)} className={primaryButton}>Send to Ren</button>
      </div>
    </div>
  )
}
