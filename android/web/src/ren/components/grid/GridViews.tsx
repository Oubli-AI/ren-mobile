/**
 * A sheet's rows in AppSheet's views beside its cards (https://support.google.com/appsheet/answer/10106772): a chart
 * of each column of choices or numbers, the rows on a calendar by their date, their places on a map and their
 * pictures in a gallery. Each draws the rows of the sheet in view and opens a row where one is tapped; `GridRows`
 * shows one at a time, or all of them as a dashboard.
 */
import { useState, type ReactNode } from 'react'
import { Bar, BarChart, LabelList, XAxis, YAxis } from 'recharts'
import {
  CalendarDays, ChartColumn, ChevronLeft, ChevronRight, ImageOff, Images, LayoutDashboard, LayoutList, Map as MapIcon, MapPin,
  type LucideIcon,
} from 'lucide-react'
import { EMBED_ALLOW, EMBED_SANDBOX } from '@/components/board/task-detail/editor/extensions/blocks/EmbedBlock'
import { ChartCard } from '@/components/projects/dashboard/ChartFrame'
import { AXIS, VIZ_VARS, formatNumber, plural } from '@/components/projects/dashboard/viz'
import { mapsEmbed } from '@/lib/editor-blocks'
import { cellDay, type CellTone, type GridCell, type GridColumn, type RowView, type RuleColor } from '@/lib/grid'
import { cn } from '@/lib/utils'

/** The rows of the sheet in view, as a view draws them. */
export interface SheetRows {
  columns: readonly GridColumn[]
  /** The column a row is known by. */
  label: number
  /** Each row of data by its place in the sheet, 1 being the row under the header. */
  rows: readonly number[]
  /** A cell's value: a formula's result. */
  value: (row: number, column: number) => GridCell | undefined
  /** What a cell shows. */
  text: (row: number, column: number) => string
  /** What a row is known by. */
  title: (row: number) => string
  /** The classes a cell takes from the sheet's color rules. */
  tone: (row: number, column: number) => string | undefined
  /** Open a row over the view. */
  open: (row: number) => void
}

export const ROW_VIEWS: Record<RowView, { label: string; icon: LucideIcon }> = {
  cards: { label: 'Cards', icon: LayoutList },
  chart: { label: 'Chart', icon: ChartColumn },
  calendar: { label: 'Calendar', icon: CalendarDays },
  map: { label: 'Map', icon: MapIcon },
  gallery: { label: 'Gallery', icon: Images },
  dashboard: { label: 'Dashboard', icon: LayoutDashboard },
}

/** A color rule's color as text, in Light and Dark. */
export const TONES: Record<RuleColor, string> = {
  red: 'text-red-600 dark:text-red-400',
  yellow: 'text-yellow-600 dark:text-yellow-400',
  green: 'text-green-600 dark:text-green-400',
  blue: 'text-blue-600 dark:text-blue-400',
  purple: 'text-blurple-600 dark:text-blurple-400',
  gray: 'text-ink-muted dark:text-ink-inverse-muted',
}

/** The classes the color rules give a cell. */
export const toneClass = (tone: CellTone | null): string | undefined => (tone ? cn(TONES[tone.color], tone.bold && 'font-bold') : undefined)

/** A step over the open spreadsheet (a row, a form) and its bar. */
export const STEP = 'fixed inset-0 z-10 flex flex-col bg-surface-0 font-sans text-ink dark:bg-surface-900 dark:text-ink-inverse'
export const BAR = 'flex h-12 shrink-0 items-center gap-1 border-b border-surface-200 px-2 dark:border-surface-800'
export const CARD = 'rounded-xl border border-surface-200 bg-surface-0 transition-colors duration-150 hover:border-surface-300 dark:border-surface-800 dark:bg-surface-900 dark:hover:border-surface-700'
export const ICON = 'flex size-9 shrink-0 items-center justify-center rounded-lg text-ink-secondary transition-colors duration-150 hover:bg-surface-100 hover:text-ink disabled:pointer-events-none disabled:opacity-40 dark:text-ink-inverse-secondary dark:hover:bg-surface-850 dark:hover:text-ink-inverse [&_svg]:size-4'
const LINE = 'block truncate text-[12.5px] leading-5 text-ink-secondary dark:text-ink-inverse-secondary'
const TITLE = 'block truncate text-[14px] font-semibold text-ink dark:text-ink-inverse'

/** A row as a card: its picture, what it is known by (in `tone`, from a color rule) and a few of its cells. A tap opens it. */
export function RowCard({ title, tone, picture, lines, onOpen }: {
  title: string; tone?: string | undefined; picture?: string | undefined; lines: readonly ReactNode[]; onOpen: () => void
}) {
  return <button type="button" onClick={onOpen} className={cn(CARD, 'flex w-full items-center gap-3 p-3 text-left')}>
    {picture && <img src={picture} alt="" loading="lazy" className="size-12 shrink-0 rounded-lg object-cover" />}
    <span className="min-w-0 flex-1">
      <span className={cn(TITLE, tone)}>{title}</span>
      {lines.map((line, index) => <span key={index} className={LINE}>{line}</span>)}
    </span>
    <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
  </button>
}

/** The rows as their pictures, from the sheet's first column of images. */
export function Gallery({ sheet }: { sheet: SheetRows }) {
  const image = sheet.columns.findIndex((column) => column.type === 'image')
  return <ul className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4">
    {sheet.rows.map((r) => {
      const picture = sheet.text(r, image)
      return <li key={r}>
        <button type="button" onClick={() => sheet.open(r)} className={cn(CARD, 'block w-full overflow-hidden text-left')}>
          {picture
            ? <img src={picture} alt="" loading="lazy" className="aspect-square w-full object-cover" />
            : <span className="flex aspect-square w-full items-center justify-center bg-surface-100 text-muted-foreground dark:bg-surface-850"><ImageOff className="size-5" /></span>}
          <span className={cn(TITLE, 'px-2.5 py-2 text-[13px]', sheet.tone(r, sheet.label))}>{sheet.title(r)}</span>
        </button>
      </li>
    })}
  </ul>
}

const DAY = 86_400_000
const monthName = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric', timeZone: 'UTC' })
const dayName = new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'long', day: 'numeric', timeZone: 'UTC' })
const weekday = new Intl.DateTimeFormat(undefined, { weekday: 'narrow', timeZone: 'UTC' })
/** The days of the week from Sunday, as 2023-01-01 was one. */
const WEEK = Array.from({ length: 7 }, (_, d) => weekday.format(Date.UTC(2023, 0, 1 + d)))
const isoDay = (ms: number) => new Date(ms).toISOString().slice(0, 10)

/** A month of days with the rows on each, by the sheet's first column of dates, and the rows of the day picked. */
export function Calendar({ sheet }: { sheet: SheetRows }) {
  const at = sheet.columns.findIndex((column) => column.type === 'date' || column.type === 'datetime')
  const byDay = new Map<string, number[]>()
  for (const r of sheet.rows) {
    const day = cellDay(sheet.value(r, at))
    if (day) byDay.set(day, [...(byDay.get(day) ?? []), r])
  }
  const now = new Date()
  const today = isoDay(now.getTime() - now.getTimezoneOffset() * 60_000)
  // It opens on the next day with rows, or else the last one.
  const [picked, setPicked] = useState(() => {
    const days = [...byDay.keys()].sort()
    return days.find((day) => day >= today) ?? days.at(-1) ?? today
  })
  const [month, setMonth] = useState(() => Date.parse(`${picked.slice(0, 7)}-01`))
  const first = new Date(month)
  const length = new Date(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + 1, 0)).getUTCDate()
  const start = month - first.getUTCDay() * DAY
  const days = Array.from({ length: Math.ceil((first.getUTCDay() + length) / 7) * 7 }, (_, index) => isoDay(start + index * DAY))
  const shown = isoDay(month).slice(0, 7)
  const turn = (by: number) => setMonth(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + by, 1))
  const rows = byDay.get(picked) ?? []

  return <div>
    <div className="flex items-center gap-1">
      <p className="min-w-0 flex-1 truncate px-1 text-sm font-semibold">{monthName.format(month)}</p>
      <button type="button" aria-label="Previous month" className={ICON} onClick={() => turn(-1)}><ChevronLeft /></button>
      <button type="button" aria-label="Next month" className={ICON} onClick={() => turn(1)}><ChevronRight /></button>
    </div>
    <div className="grid grid-cols-7 text-center">
      {WEEK.map((name, index) => <span key={index} aria-hidden className="py-1 text-[11px] font-medium text-muted-foreground">{name}</span>)}
      {days.map((day) => {
        const count = byDay.get(day)?.length ?? 0
        const chosen = day === picked
        return <button key={day} type="button" aria-pressed={chosen} aria-label={`${dayName.format(Date.parse(day))}: ${plural(count, 'row')}`}
          onClick={() => setPicked(day)}
          className={cn('flex h-11 flex-col items-center justify-center gap-1 rounded-lg text-[13px] tabular-nums transition-colors duration-150',
            chosen
              ? 'bg-blurple-600 font-semibold text-white dark:bg-blurple-400 dark:text-ink'
              : cn('hover:bg-surface-100 dark:hover:bg-surface-850', day === today && 'font-semibold text-blurple-600 dark:text-blurple-400',
                day.slice(0, 7) !== shown && 'text-muted-foreground'))}>
          {Number(day.slice(8))}
          <span aria-hidden className={cn('size-1 rounded-full', count === 0 ? 'invisible' : chosen ? 'bg-white dark:bg-ink' : 'bg-blurple-600 dark:bg-blurple-400')} />
        </button>
      })}
    </div>
    <p className="mt-3 px-1 text-[12.5px] font-medium text-muted-foreground">{dayName.format(Date.parse(picked))}</p>
    {rows.length > 0
      ? <ul className="mt-2 space-y-2">
        {rows.map((r) => <li key={r}>
          <RowCard title={sheet.title(r)} tone={sheet.tone(r, sheet.label)} lines={[<span className={sheet.tone(r, at)}>{sheet.text(r, at)}</span>]} onOpen={() => sheet.open(r)} />
        </li>)}
      </ul>
      : <p className="px-1 py-3 text-[13px] text-muted-foreground">No rows on this day.</p>}
  </div>
}

/**
 * The rows with a place, from the sheet's first column of places, and the one picked on Google Maps' embedded map,
 * which needs no key (`mapsEmbed`, as an Embed tab's map).
 */
export function Places({ sheet }: { sheet: SheetRows }) {
  const at = sheet.columns.findIndex((column) => column.type === 'latlong' || column.type === 'address')
  const placed = sheet.rows.filter((r) => sheet.text(r, at))
  const [picked, setPicked] = useState<number | null>(null)
  const shown = picked !== null && placed.includes(picked) ? picked : placed[0]
  const map = shown === undefined ? null : mapsEmbed(sheet.text(shown, at))

  return <div>
    {shown !== undefined && map?.ok && (
      // A frame of its own for each place: a frame sent to another address adds to the tab's history, which Back walks.
      <iframe key={map.value.src} src={map.value.src} title={`${sheet.title(shown)} on Google Maps`} sandbox={EMBED_SANDBOX} allow={EMBED_ALLOW}
        referrerPolicy="strict-origin-when-cross-origin" className="block h-72 w-full rounded-xl border-0 bg-surface-100 dark:bg-surface-850" />
    )}
    <ul className="mt-2 space-y-2">
      {placed.map((r) => (
        <li key={r} className="flex items-center gap-1">
          <button type="button" aria-pressed={r === shown} onClick={() => setPicked(r)}
            className={cn(CARD, 'flex min-w-0 flex-1 items-center gap-3 p-3 text-left',
              r === shown && 'border-blurple-600 hover:border-blurple-600 dark:border-blurple-400 dark:hover:border-blurple-400')}>
            <MapPin className={cn('size-4 shrink-0', r === shown ? 'text-blurple-600 dark:text-blurple-400' : 'text-muted-foreground')} />
            <span className="min-w-0 flex-1">
              <span className={cn(TITLE, sheet.tone(r, sheet.label))}>{sheet.title(r)}</span>
              <span className={cn(LINE, sheet.tone(r, at))}>{sheet.text(r, at)}</span>
            </span>
          </button>
          <button type="button" aria-label={`Open ${sheet.title(r)}`} className={ICON} onClick={() => sheet.open(r)}><ChevronRight /></button>
        </li>
      ))}
    </ul>
  </div>
}

/** The most bars a chart of numbers shows: the rows with the highest. */
const TOP = 12
const BAR_HEIGHT = 30
const listed = new Intl.ListFormat('en', { type: 'conjunction' })

/** The bars level with the first, by name when there are three or fewer; null when the first stands alone. */
function tied(bars: { label: string; value: number }[], noun: string): string | null {
  const level = bars.filter((bar) => bar.value === bars[0]?.value)
  if (level.length < 2) return null
  return level.length > 3 ? plural(level.length, noun) : listed.format(level.map((bar) => bar.label))
}

/**
 * A chart of each column of choices (how many rows have each) and of numbers (each row's, highest first), with its
 * table one tap away, as the project dashboard's charts are.
 */
export function Charts({ sheet }: { sheet: SheetRows }) {
  const label = sheet.columns[sheet.label]?.name ?? ''
  const charts = sheet.columns.flatMap((column, c) => {
    if (column.type === 'enum') {
      const bars = column.options.map((option) => {
        const count = sheet.rows.filter((r) => sheet.value(r, c) === option).length
        return { label: option, value: count, shown: formatNumber(count) }
      }).sort((a, b) => b.value - a.value)
      const total = bars.reduce((sum, bar) => sum + bar.value, 0)
      const [top] = bars
      const level = tied(bars, 'choice')
      return top && total > 0 ? [{
        c, column, bars, header: [column.name, 'Rows'],
        question: `How many rows have each ${column.name}?`,
        takeaway: level
          ? `${level} tie for the most, with ${plural(top.value, 'row')} each of ${formatNumber(total)}.`
          : `${top.label} is the most common, with ${plural(top.value, 'row')} of ${formatNumber(total)}.`,
        howToRead: `Each bar counts the rows with that ${column.name}.`,
      }] : []
    }
    if (column.type !== 'number' && column.type !== 'price' && column.type !== 'percent') return []
    const bars = sheet.rows.flatMap((r) => {
      const value = sheet.value(r, c)
      return typeof value === 'number' ? [{ label: sheet.title(r), value, shown: sheet.text(r, c) }] : []
    }).sort((a, b) => b.value - a.value)
    const [top] = bars
    const level = tied(bars, 'row')
    return top ? [{
      c, column, bars, header: [label, column.name],
      question: `Which rows have the highest ${column.name}?`,
      takeaway: `${level ? `${level} tie for the highest` : `${top.label} has the highest`}, ${top.shown}.${bars.length > TOP ? ` The ${TOP} highest of ${formatNumber(bars.length)} rows are shown.` : ''}`,
      howToRead: `Each bar is one row's ${column.name}, highest first.`,
    }] : []
  })

  return <div className={cn(VIZ_VARS, 'space-y-3')}>
    {charts.map(({ c, column, bars, header, question, takeaway, howToRead }) => {
      const drawn = bars.slice(0, TOP)
      return <ChartCard key={c} title={column.name} question={question} takeaway={takeaway} howToRead={howToRead}
        table={{ caption: column.name, columns: header, rows: bars.map((bar) => [bar.label, bar.shown]) }}>
        <BarChart layout="vertical" data={drawn} responsive style={{ width: '100%', height: drawn.length * BAR_HEIGHT + 8 }}
          margin={{ top: 4, right: 72, bottom: 4, left: 0 }}>
          <XAxis type="number" hide />
          <YAxis type="category" dataKey="label" width={112} interval={0} {...AXIS} axisLine={false}
            tickFormatter={(value: string) => (value.length > 16 ? `${value.slice(0, 15)}…` : value)} />
          <Bar dataKey="value" fill="var(--viz-progress)" maxBarSize={18} radius={[0, 4, 4, 0]} isAnimationActive={false}>
            <LabelList dataKey="shown" position="right" fill="var(--viz-ink)" fontSize={11} />
          </Bar>
        </BarChart>
      </ChartCard>
    })}
  </div>
}
