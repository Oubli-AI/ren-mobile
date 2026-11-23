/**
 * A sheet's rows as an app, after AppSheet's views (https://support.google.com/appsheet/answer/10106772): a card per
 * row or another of the views the sheet's data gives it (`GridViews`), a row with its previous and next, and a form
 * that adds a row or changes one. A column's type (`gridColumns`) decides how a cell shows, what a tap on it starts
 * (a call, a text, an email, its link, its place on a map, as AppSheet's external actions do:
 * https://support.google.com/appsheet/answer/10107706) and which input the form gives it.
 */
import { useId, useMemo, useRef, useState, type FormEvent, type ReactNode, type RefObject } from 'react'
import { ArrowLeft, ChevronLeft, ChevronRight, Copy, ExternalLink, Loader2, Mail, MapPin, MessageSquare, Pencil, Phone, Plus, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import {
  gridColumns, isFormula, rowViews, temporalSerial, temporalText,
  type ColumnType, type Grid, type GridCell, type GridColumn, type GridDisplaySheet, type RowView,
} from '@/lib/grid'
import { cn } from '@/lib/utils'
import { Calendar, Charts, Gallery, ICON, Places, ROW_VIEWS, RowCard, type SheetRows } from './GridViews'

/** What an open spreadsheet holds: its grid, each cell's value and what each shows by sheet, and the sheet in view. */
export interface GridRowsValue { grid: Grid; values: GridCell[][][]; display: GridDisplaySheet[]; active: number }

/** A row's cells for `GridEngine.write`: undefined keeps what a cell holds. */
type RowCells = readonly (GridCell | undefined)[]

/** What lies over the cards: a row, or the form on a row (null for a new one) filled from a row (null for an empty form). */
type Step = { kind: 'row'; row: number } | { kind: 'form'; row: number | null; from: number | null }

const NUMBER = /^-?\d+(?:\.\d+)?$/

/** The input a form gives a column, where it isn't a line of text. */
const INPUT: Partial<Record<ColumnType, string>> = {
  number: 'number', price: 'number', percent: 'number', date: 'date', datetime: 'datetime-local', time: 'time',
  email: 'email', phone: 'tel', url: 'url', image: 'url',
}

/** What a tap on a cell starts, by its column's type. */
function actions(type: ColumnType, text: string): { label: string; href: string; icon: ReactNode; tab?: true }[] {
  if (type === 'email') return [{ label: `Email ${text}`, href: `mailto:${text}`, icon: <Mail /> }]
  if (type === 'phone') {
    const number = text.replace(/[^\d+]/g, '')
    return [{ label: `Call ${text}`, href: `tel:${number}`, icon: <Phone /> }, { label: `Text ${text}`, href: `sms:${number}`, icon: <MessageSquare /> }]
  }
  if (type === 'url' || type === 'image') return [{ label: 'Open the link', href: text, icon: <ExternalLink />, tab: true }]
  // Google's Maps URLs (https://developers.google.com/maps/documentation/urls/get-started#search-action).
  if (type === 'address' || type === 'latlong') return [{ label: 'Open the map', href: `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(text)}`, icon: <MapPin />, tab: true }]
  return []
}

/** What a form field starts with: the cell as its input takes it. */
function entered(column: GridColumn, cell: GridCell | undefined): string | boolean {
  if (column.type === 'yesno') return cell === true
  if (cell === null || cell === undefined) return ''
  if (typeof cell !== 'number') return column.type === 'datetime' ? String(cell).replace(' ', 'T') : String(cell)
  if (column.type === 'percent') return String(Number((cell * 100).toPrecision(12)))
  return column.type === 'date' || column.type === 'datetime' || column.type === 'time' ? temporalText(column.type, cell) : String(cell)
}

const BAR = 'flex h-12 shrink-0 items-center gap-1 border-b border-surface-200 px-2 dark:border-surface-800'
const STEP = 'fixed inset-0 z-10 flex flex-col bg-surface-0 font-sans text-ink dark:bg-surface-900 dark:text-ink-inverse'

/** The sheets of a spreadsheet as tabs. */
export function SheetTabs({ names, active, onShow }: { names: readonly string[]; active: number; onShow: (sheet: number) => void }) {
  return <div role="tablist" aria-label="Sheets" className="flex shrink-0 gap-1 overflow-x-auto border-b border-surface-200 px-2 py-1.5 scrollbar-none dark:border-surface-800">
    {names.map((name, index) => (
      <button key={name} type="button" role="tab" aria-selected={index === active} onClick={() => onShow(index)}
        className={cn('h-7 shrink-0 rounded-md px-2.5 text-[12px] font-medium transition-colors duration-150',
          index === active
            ? 'bg-surface-100 text-ink dark:bg-surface-800 dark:text-ink-inverse'
            : 'text-ink-muted hover:text-ink dark:text-ink-inverse-muted dark:hover:text-ink-inverse')}>
        {name}
      </button>
    ))}
  </div>
}

export function GridRows({ value, view, editable, leave, onShow, onWrite }: {
  value: GridRowsValue
  /** How the rows show. */
  view: RowView
  /** Whether rows can be added, changed and removed. */
  editable: boolean
  /** Set here to what Back does while a row or the form is open: it leaves that step and answers true. */
  leave: RefObject<() => boolean>
  /** Bring another sheet into view. */
  onShow: (sheet: number) => void
  /** Write a row of the sheet in view (`GridEngine.write`), and read the spreadsheet again. */
  onWrite: (row: number, cells: RowCells | null, fill?: boolean) => Promise<void>
}) {
  const sheet = value.grid.sheets[value.active]
  const written = sheet?.rows ?? []
  const shown = value.display[value.active]?.rows ?? []
  const results = value.values[value.active]
  const columns = useMemo(() => (sheet ? gridColumns(sheet, results) : []), [sheet, results])
  // A row is known by its first column of text, as AppSheet labels one.
  const label = Math.max(0, columns.findIndex((column) => column.type === 'text'))
  const image = columns.findIndex((column) => column.type === 'image')
  const summary = columns.flatMap((column, c) => (c === label || c === image || column.type === 'longtext' ? [] : [c])).slice(0, 3)
  const count = Math.max(0, written.length - 1)

  // A row and the form are steps in the tab's history, as the open spreadsheet is, so Back leaves them first.
  const [steps, setSteps] = useState<Step[]>([])
  const step = steps.at(-1)
  const enter = (next: Step) => {
    window.history.pushState(null, '')
    setSteps((all) => [...all, next])
  }
  const turn = (next: Step) => setSteps((all) => [...all.slice(0, -1), next])
  leave.current = () => {
    if (steps.length === 0) return false
    setSteps((all) => all.slice(0, -1))
    return true
  }
  // What a save does once it is written depends on its step still being the one in view: Back may have left it.
  const top = useRef(step)
  top.current = step

  const text = (r: number, c: number): string => {
    const cell = shown[r]?.[c] ?? ''
    return columns[c]?.type !== 'yesno' ? cell : cell === 'TRUE' ? 'Yes' : cell === 'FALSE' ? 'No' : cell
  }
  const title = (r: number) => text(r, label) || `Row ${r}`
  const sheetRows: SheetRows = {
    columns, label, rows: written.slice(1).map((_, index) => index + 1), value: (r, c) => results?.[r]?.[c], text, title,
    open: (row) => enter({ kind: 'row', row }),
  }

  const body = (each: RowView): ReactNode => {
    switch (each) {
      case 'cards':
        return <ul aria-label={sheet?.name} className="grid auto-rows-min gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {sheetRows.rows.map((r) => (
            <li key={r}>
              <RowCard title={title(r)} picture={image < 0 ? undefined : text(r, image)} onOpen={() => enter({ kind: 'row', row: r })}
                lines={summary.flatMap((c) => (text(r, c) ? [<><span className="text-muted-foreground">{columns[c]?.name}</span> {text(r, c)}</>] : []))} />
            </li>
          ))}
        </ul>
      case 'chart':
        return <Charts sheet={sheetRows} />
      case 'calendar':
        return <Calendar sheet={sheetRows} />
      case 'map':
        return <Places sheet={sheetRows} />
      case 'gallery':
        return <Gallery sheet={sheetRows} />
      case 'dashboard':
        return <div className="space-y-6">
          {rowViews(columns).filter((part) => part !== 'cards' && part !== 'dashboard').map((part) => (
            <section key={part} aria-label={ROW_VIEWS[part].label} className="space-y-2">
              <h3 className="px-1 text-[12px] font-semibold text-muted-foreground">{ROW_VIEWS[part].label}</h3>
              {body(part)}
            </section>
          ))}
        </div>
    }
  }

  return <>
    {value.grid.sheets.length > 1 && <SheetTabs names={value.grid.sheets.map((each) => each.name)} active={value.active} onShow={onShow} />}
    <div className="flex h-11 shrink-0 items-center justify-between gap-2 px-4">
      <p className="truncate text-[12.5px] text-muted-foreground">{count} {count === 1 ? 'row' : 'rows'}</p>
      {editable && columns.length > 0 && (
        <Button type="button" size="sm" className="h-8 gap-1.5 rounded-full" onClick={() => enter({ kind: 'form', row: null, from: null })}><Plus />Add row</Button>
      )}
    </div>
    {count > 0
      ? <div className="min-h-0 flex-1 overflow-y-auto px-3 pb-3">{body(view)}</div>
      : <p className="min-h-0 flex-1 px-4 py-10 text-center text-[13px] text-muted-foreground">{columns.length > 0 ? 'This sheet has no rows yet.' : 'This sheet is empty.'}</p>}
    {step?.kind === 'row' && (
      <Row key={step.row} columns={columns} title={title(step.row)} picture={image < 0 ? '' : text(step.row, image)} text={(c) => text(step.row, c)}
        row={step.row} count={count} editable={editable} onTurn={(row) => turn({ kind: 'row', row })}
        onEdit={() => enter({ kind: 'form', row: step.row, from: step.row })}
        onCopy={() => enter({ kind: 'form', row: null, from: step.row })}
        onRemove={async () => {
          await onWrite(step.row, null)
          if (top.current === step) window.history.back()
        }} />
    )}
    {step?.kind === 'form' && (
      <RowForm columns={columns} written={written} results={results ?? []} shown={shown} row={step.row} from={step.from}
        onSave={async (cells) => {
          // A changed row is the step under the form. A new row goes under the last one, filled down from it (a
          // sheet's first row of data is typed in whole), and takes the form's place.
          if (step.row !== null) {
            await onWrite(step.row, cells)
            if (top.current === step) window.history.back()
          } else {
            await onWrite(written.length, cells, written.length > 1)
            if (top.current === step) turn({ kind: 'row', row: written.length })
          }
        }} />
    )}
  </>
}

/** One row over the whole screen: every cell with what a tap on it starts, the rows before and after it, and what can be done to it. */
function Row({ columns, title, picture, text, row, count, editable, onTurn, onEdit, onCopy, onRemove }: {
  columns: readonly GridColumn[]
  title: string
  picture: string
  text: (column: number) => string
  row: number
  count: number
  editable: boolean
  onTurn: (row: number) => void
  onEdit: () => void
  onCopy: () => void
  onRemove: () => Promise<void>
}) {
  const [removing, setRemoving] = useState<'asked' | 'busy' | null>(null)
  const [error, setError] = useState('')
  const remove = () => {
    setRemoving('busy')
    onRemove().catch(() => {
      setRemoving(null)
      setError('The row could not be deleted.')
    })
  }

  return <div role="dialog" aria-modal="true" aria-label={title} className={STEP}>
    <div className={BAR}>
      <button type="button" aria-label="Back" className={ICON} onClick={() => window.history.back()}><ArrowLeft /></button>
      <p className="min-w-0 flex-1 truncate text-sm font-semibold">{title}</p>
      <p className="shrink-0 text-[12px] tabular-nums text-muted-foreground">{row} of {count}</p>
      <button type="button" aria-label="Previous row" className={ICON} disabled={row <= 1} onClick={() => onTurn(row - 1)}><ChevronLeft /></button>
      <button type="button" aria-label="Next row" className={ICON} disabled={row >= count} onClick={() => onTurn(row + 1)}><ChevronRight /></button>
    </div>
    <div className="min-h-0 flex-1 overflow-y-auto">
      {picture && <img src={picture} alt="" className="max-h-60 w-full object-cover" />}
      <dl>
        {columns.map((column, c) => {
          const shown = text(c)
          return <div key={c} className="flex items-center gap-1 border-b border-surface-200 py-2 pl-4 pr-2 dark:border-surface-800">
            <div className="min-w-0 flex-1">
              <dt className="text-[12px] leading-5 text-muted-foreground">{column.name}</dt>
              <dd className="min-h-5 whitespace-pre-wrap break-words text-[14px] leading-5">
                {column.type === 'enum' && shown
                  ? <span className="inline-block rounded-full bg-surface-100 px-2 text-[12.5px] font-medium dark:bg-surface-800">{shown}</span>
                  : shown}
              </dd>
            </div>
            {shown && actions(column.type, shown).map((action) => (
              <a key={action.href} href={action.href} aria-label={action.label} title={action.label} className={ICON}
                {...(action.tab ? { target: '_blank', rel: 'noopener noreferrer' } : {})}>
                {action.icon}
              </a>
            ))}
          </div>
        })}
      </dl>
    </div>
    {editable && (
      <div className="flex shrink-0 items-center gap-2 border-t border-surface-200 p-2 dark:border-surface-800">
        {removing
          ? <>
            <p className="min-w-0 flex-1 truncate px-2 text-[13px]">Delete this row?</p>
            <Button type="button" size="sm" variant="ghost" className="h-9 rounded-full" disabled={removing === 'busy'} onClick={() => setRemoving(null)}>Keep</Button>
            <Button type="button" size="sm" variant="destructive" className="h-9 gap-1.5 rounded-full" disabled={removing === 'busy'} onClick={remove}>
              {removing === 'busy' ? <Loader2 className="animate-spin" /> : <Trash2 />}Delete
            </Button>
          </>
          : <>
            <Button type="button" size="sm" className="h-9 gap-1.5 rounded-full" onClick={onEdit}><Pencil />Edit</Button>
            <Button type="button" size="sm" variant="outline" className="h-9 gap-1.5 rounded-full" onClick={onCopy}><Copy />Copy</Button>
            {error && <p role="alert" className="min-w-0 flex-1 truncate text-[12px] text-red-600 dark:text-red-400">{error}</p>}
            <Button type="button" size="sm" variant="ghost" className="ml-auto h-9 gap-1.5 rounded-full text-red-600 hover:text-red-600 dark:text-red-400 dark:hover:text-red-400" onClick={() => setRemoving('asked')}><Trash2 />Delete</Button>
          </>}
      </div>
    )}
  </div>
}

/**
 * The form over the whole screen: an input per column by its type. A cell that is a formula shows its result and
 * isn't asked for, and a new row gets the formulas of the row above it.
 */
function RowForm({ columns, written, results, shown, row, from, onSave }: {
  columns: readonly GridColumn[]
  written: readonly (readonly GridCell[])[]
  results: readonly (readonly GridCell[])[]
  shown: readonly (readonly string[])[]
  /** The row being changed, or null for a new one. */
  row: number | null
  /** The row the form starts from, or null for an empty form. */
  from: number | null
  onSave: (cells: RowCells) => Promise<void>
}) {
  const id = useId()
  // A new row is filled down from the last one, so it is calculated where that row is.
  const model = row ?? written.length - 1
  const calculated = (c: number) => model > 0 && isFormula(written[model]?.[c])
  const [fields, setFields] = useState(() => columns.map((column, c) => entered(column, from === null ? null : written[from]?.[c])))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const set = (c: number, field: string | boolean) => setFields((all) => all.map((each, index) => (index === c ? field : each)))

  /** A field as the cell its column keeps: a number where the column's cells are numbers, text where they are text. */
  const cell = (column: GridColumn, c: number): GridCell | undefined => {
    if (calculated(c)) return undefined
    const field = fields[c]
    if (typeof field === 'boolean') return field
    const typed = (field ?? '').trim()
    if (!typed) return null
    const kept = results.slice(1).map((each) => each[c]).find((value) => value !== null && value !== undefined && value !== '')
    switch (column.type) {
      case 'number':
      case 'price':
        return Number.isFinite(Number(typed)) ? Number(typed) : null
      case 'percent':
        return Number.isFinite(Number(typed)) ? Number(typed) / 100 : null
      case 'date':
      case 'datetime':
      case 'time':
        if (typeof kept === 'number') return temporalSerial(column.type, typed)
        return typeof kept === 'string' && kept.includes(' ') ? typed.replace('T', ' ') : typed
      case 'text':
        // A number typed into a column of text is a number, as in a spreadsheet, unless the column keeps numbers as text.
        return NUMBER.test(typed) && !written.some((each, r) => r > 0 && typeof each[c] === 'string' && NUMBER.test(each[c])) ? Number(typed) : typed
      default:
        return typed
    }
  }

  const save = (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError('')
    onSave(columns.map(cell)).catch(() => {
      setBusy(false)
      setError('The row could not be saved.')
    })
  }

  return <form role="dialog" aria-modal="true" aria-label={row === null ? 'New row' : 'Edit row'} className={STEP} onSubmit={save}>
    <div className={BAR}>
      <button type="button" aria-label="Cancel" className={ICON} disabled={busy} onClick={() => window.history.back()}><ArrowLeft /></button>
      <p className="min-w-0 flex-1 truncate text-sm font-semibold">{row === null ? 'New row' : 'Edit row'}</p>
      {error && <p role="alert" className="min-w-0 truncate text-[12px] text-red-600 dark:text-red-400">{error}</p>}
      <Button type="submit" size="sm" className="mr-1 h-8 gap-1.5 rounded-full" disabled={busy}>{busy && <Loader2 className="animate-spin" />}Save</Button>
    </div>
    <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-4">
      {columns.map((column, c) => {
        const field = fields[c]
        const name = `${id}-${c}`
        return <div key={c}>
          <label htmlFor={name} className="mb-1 block text-[12px] font-medium text-muted-foreground">{column.name}{column.type === 'percent' ? ' (%)' : ''}</label>
          {calculated(c)
            ? <p id={name} className="min-h-9 rounded-md bg-surface-100 px-3 py-2 text-[14px] leading-5 text-ink-secondary dark:bg-surface-850 dark:text-ink-inverse-secondary">{row === null ? 'Calculated' : shown[row]?.[c]}</p>
            : typeof field === 'boolean'
              ? <Switch id={name} checked={field} onCheckedChange={(checked) => set(c, checked)} />
              : column.type === 'longtext'
                ? <Textarea id={name} rows={4} value={field ?? ''} onChange={(event) => set(c, event.target.value)} />
                : <>
                  <Input id={name} type={INPUT[column.type] ?? 'text'} value={field ?? ''} onChange={(event) => set(c, event.target.value)}
                    {...(INPUT[column.type] === 'number' ? { step: 'any', inputMode: 'decimal' as const } : {})}
                    {...(column.type === 'enum' ? { list: `${name}-options`, autoComplete: 'off' } : {})} />
                  {column.type === 'enum' && <datalist id={`${name}-options`}>{column.options.map((option) => <option key={option} value={option} />)}</datalist>}
                </>}
        </div>
      })}
    </div>
  </form>
}
