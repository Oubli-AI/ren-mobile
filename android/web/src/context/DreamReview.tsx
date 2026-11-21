import { useState } from "react"
import { field, ghostButton, mutedText, primaryButton } from "@/components/interests/contextUi"
import { dreamTopicLabel, type Dream, type DreamTopic, type TopicReview } from "@/lib/context-api"
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

/** What a topic read before the dream: ren's `/context/dream` sends it beside ren desktop's `DreamTopic` fields. */
const before = (topic: DreamTopic): string | null =>
  "before" in topic && typeof topic.before === "string" ? topic.before : null

/**
 * The dream's review on the phone, over the whole view: every topic the dream would change, with what it reads now
 * and what it would read (a new topic's whole text, a removed topic's whole text), the lines that change marked.
 * Nothing the dream wrote is in memory yet: the topics left checked go in when the checklist is sent (`reviewDream`),
 * and the unchecked ones go back to the dreaming agent with their comments.
 */
export function DreamReview({ dream }: { dream: Dream }) {
  const { setReviewingDream, reviewDream } = useContextAgentStore.getState()
  const [review, setReview] = useState<TopicReview[]>(() => dream.topics.map(({ key }) => ({ key, right: true, comment: "" })))
  const mark = (index: number, patch: Partial<TopicReview>) =>
    setReview(review.map((entry, at) => at === index ? { ...entry, ...patch } : entry))

  return (
    <div data-testid="dream-review" className="fixed inset-3 z-50 flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-3 text-ink shadow-float dark:border-surface-800 dark:bg-surface-900 dark:text-ink-inverse">
      <p className="text-[13px] font-semibold">Check what Ren wants to remember</p>
      <p className={cn("mt-1", mutedText)}>
        Each topic shows what Ren's memory reads now and what it would read. The checked ones go into its memory when
        you approve. Uncheck anything wrong and say why: it stays out, Ren redoes it in its next dream and keeps your
        notes for every dream after.
      </p>
      <ul className="mt-2 min-h-0 flex-1 space-y-2 overflow-y-auto">
        {dream.topics.map((topic, index) => {
          const entry = review[index]
          if (!entry) return null
          const label = dreamTopicLabel(topic)
          const was = before(topic)
          const now = topic.text
          const kind = now === null ? "Removed" : was === null ? "New" : "Changed"
          const wasLines = new Set(was?.split("\n") ?? [])
          const nowLines = new Set(now?.split("\n") ?? [])
          return (
            <li key={topic.key} className="rounded-lg border border-surface-200 p-2.5 dark:border-surface-800">
              <label className="flex items-start gap-2">
                <input type="checkbox" checked={entry.right} onChange={(event) => mark(index, { right: event.target.checked })}
                  aria-label={`Remember ${label.title}`} className="mt-0.5 h-4 w-4 flex-shrink-0 accent-blurple-600 dark:accent-blurple-400" />
                <span className="min-w-0 flex-1 text-[13px] font-semibold">{label.title}
                  <span className="font-normal text-ink-muted dark:text-ink-inverse-muted"> · {label.agent}{kind && ` · ${kind}`}</span>
                </span>
              </label>
              {was !== null && <>
                <p className="mt-2 text-[11px] font-semibold uppercase tracking-wide text-ink-muted dark:text-ink-inverse-muted">Now</p>
                <Lines text={was} other={nowLines} tone="removed" />
              </>}
              {now !== null && <>
                <p className="mt-2 text-[11px] font-semibold uppercase tracking-wide text-ink-muted dark:text-ink-inverse-muted">{was === null ? "New text" : "After"}</p>
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
        <button type="button" onClick={() => void reviewDream(review)} className={primaryButton}>Approve checked</button>
      </div>
    </div>
  )
}
