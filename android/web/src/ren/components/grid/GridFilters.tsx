/**
 * A sheet's slices, color rules and bots, after AppSheet's (slices: https://support.google.com/appsheet/answer/10106592,
 * format rules: https://support.google.com/appsheet/answer/10106703, bots: https://support.google.com/appsheet/answer/11998993):
 * a slice shows only the rows that pass its tests, in every view; a color rule colors the cells of the rows that pass
 * its tests; a bot hands Ren rows to work on (`agent/api/core/grid_bots.py`). A test names a column, a comparison its
 * type allows and a value, which the form takes with the input of the column's type.
 */
import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { ArrowLeft, Pencil, Plus, Trash2, X } from 'lucide-react'
import { projectInputClass } from '@/components/projects/ProjectFieldInput'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import { WEEKDAYS, cadenceLabel, defaultCadence, type AgentCadence, type ScheduleEvery } from '@/lib/context-api'
import {
  BOT_EVENTS, RULE_COLORS, fold, temporalText,
  type BotEvent, type ColumnType, type ConditionOp, type GridBot, type GridColumn, type GridCondition, type GridRule, type GridSheet,
  type GridSlice, type RuleColor,
} from '@/lib/grid'
import { cn } from '@/lib/utils'
import { BAR, CARD, ICON, STEP, TONES } from './GridViews'

const NUMBERS: readonly ColumnType[] = ['number', 'price', 'percent']
const DATES: readonly ColumnType[] = ['date', 'datetime']
/** The comparisons of an order: as numbers read them, and as dates do. */
const ORDER: readonly (readonly [ConditionOp, string, string])[] = [
  ['>', 'is more than', 'is after'], ['>=', 'is at least', 'is on or after'], ['<', 'is less than', 'is before'], ['<=', 'is at most', 'is on or before'],
]
const TAKES_NO_VALUE: readonly ConditionOp[] = ['empty', 'filled']

/** The comparisons a column's type allows, in words. */
function comparisons(type: ColumnType | undefined): [ConditionOp, string][] {
  const date = type !== undefined && DATES.includes(type)
  const kept: [ConditionOp, string][] = [['=', 'is'], ['!=', 'is not']]
  if (date || (type !== undefined && NUMBERS.includes(type))) kept.push(...ORDER.map(([op, number, day]): [ConditionOp, string] => [op, date ? day : number]))
  else kept.push(['contains', 'contains'])
  kept.push(['empty', 'is empty'], ['filled', 'is not empty'])
  return kept
}

const columnOf = (columns: readonly GridColumn[], name: string) => columns.find((column) => fold(column.name) === fold(name))

/** A test's value as its column shows it: a date as its day, a share as a percentage, yes or no. */
function shownValue(type: ColumnType | undefined, value: GridCondition['value']): string {
  if (value === undefined) return ''
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value !== 'number') return value
  if (type !== undefined && DATES.includes(type)) return temporalText('date', value)
  return type === 'percent' ? String(Number((value * 100).toPrecision(12))) : String(value)
}

/** A slice's or a rule's tests in words. */
function describe(columns: readonly GridColumn[], where: readonly GridCondition[]): string {
  if (where.length === 0) return 'Every row'
  return where.map(({ column, op, value }) => {
    const type = columnOf(columns, column)?.type
    const said = comparisons(type).find(([each]) => each === op)?.[1] ?? op
    const shown = shownValue(type, value)
    return `${column} ${said}${TAKES_NO_VALUE.includes(op) ? '' : ` ${shown}${type === 'percent' ? '%' : ''}`}`
  }).join(' and ')
}

const missing = (test: GridCondition) => !TAKES_NO_VALUE.includes(test.op) && (test.value === undefined || test.value === '')

/** When a bot runs: on rows a save adds or changes, or as often as a scheduled agent can. */
type When = BotEvent | ScheduleEvery
const WHEN: readonly (readonly [When, string])[] = [
  ['added', 'When rows are added'], ['changed', 'When rows are changed'], ['added or changed', 'When rows are added or changed'],
  ['hours', 'Every few hours'], ['day', 'Every day'], ['weekdays', 'Every weekday'], ['week', 'Every week'],
]
const isEvent = (when: When): when is BotEvent => BOT_EVENTS.some((each) => each === when)

/** When a bot runs and the rows it hands Ren, in words. */
function botLine(bot: GridBot): string {
  const when = bot.schedule ? cadenceLabel(bot.schedule) : WHEN.find(([each]) => each === bot.on)?.[1] ?? ''
  return `${when} · ${bot.slice ? `Slice ${bot.slice}` : 'Every row'}`
}

/** A sheet's slices, color rules and bots, each with Edit and Delete, and a way to add one of each. */
export function SheetFilters({ sheet, columns, onSlice, onRule, onBot, onChange }: {
  sheet: GridSheet
  columns: readonly GridColumn[]
  /** Open the form on a slice, or on a new one (null). */
  onSlice: (index: number | null) => void
  /** Open the form on a color rule, or on a new one (null). */
  onRule: (index: number | null) => void
  /** Open the form on a bot, or on a new one (null). */
  onBot: (index: number | null) => void
  onChange: (sheet: GridSheet) => void
}) {
  const slices = sheet.slices ?? []
  const rules = sheet.rules ?? []
  const bots = sheet.bots ?? []
  const item = (key: number, label: string, title: ReactNode, line: string, onEdit: () => void, onDelete: () => void) => (
    <li key={key} className={cn(CARD, 'flex items-center gap-1 py-2 pl-3 pr-1')}>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[14px] font-semibold">{title}</span>
        <span className="block text-[12.5px] leading-5 text-ink-secondary dark:text-ink-inverse-secondary">{line}</span>
      </span>
      <button type="button" aria-label={`Edit ${label}`} className={ICON} onClick={onEdit}><Pencil /></button>
      <button type="button" aria-label={`Delete ${label}`} className={ICON} onClick={onDelete}><Trash2 /></button>
    </li>
  )

  return <div role="dialog" aria-modal="true" aria-label="Slices, color rules and bots" className={STEP}>
    <div className={BAR}>
      <button type="button" aria-label="Back" className={ICON} onClick={() => window.history.back()}><ArrowLeft /></button>
      <p className="min-w-0 flex-1 truncate text-sm font-semibold">Slices, color rules and bots</p>
    </div>
    <div className="min-h-0 flex-1 space-y-6 overflow-y-auto p-4">
      <section className="space-y-2">
        <h3 className="text-[13px] font-semibold">Slices</h3>
        <p className="text-[12.5px] text-muted-foreground">A slice shows only the rows that pass its tests, in every view. Pick one above the rows.</p>
        <ul className="space-y-2">
          {slices.map((slice, index) => item(index, `the slice ${slice.name}`, slice.name, describe(columns, slice.where), () => onSlice(index),
            () => onChange({ ...sheet, slices: slices.filter((_, i) => i !== index) })))}
        </ul>
        <Button type="button" size="sm" variant="outline" className="h-8 gap-1.5 rounded-full" onClick={() => onSlice(null)}><Plus />New slice</Button>
      </section>
      <section className="space-y-2">
        <h3 className="text-[13px] font-semibold">Color rules</h3>
        <p className="text-[12.5px] text-muted-foreground">A rule colors cells in the rows that pass its tests. Where two rules color a cell, the later one wins.</p>
        <ul className="space-y-2">
          {rules.map((rule, index) => {
            const formatted = rule.columns.length > 0 ? rule.columns.join(', ') : 'Every column'
            return item(index, `the color rule on ${formatted}`,
              <span className={cn(TONES[rule.color], rule.bold && 'font-bold')}>{formatted}</span>, describe(columns, rule.where), () => onRule(index),
              () => onChange({ ...sheet, rules: rules.filter((_, i) => i !== index) }))
          })}
        </ul>
        <Button type="button" size="sm" variant="outline" className="h-8 gap-1.5 rounded-full" onClick={() => onRule(null)}><Plus />New color rule</Button>
      </section>
      <section className="space-y-2">
        <h3 className="text-[13px] font-semibold">Bots</h3>
        <p className="text-[12.5px] text-muted-foreground">
          A bot hands Ren rows to work on: the rows you add or change, or all of them on a schedule. Ren's report comes as a notification.
          Bots run in Documents, for a signed-in account.
        </p>
        <ul className="space-y-2">
          {bots.map((bot, index) => item(index, `the bot ${bot.name}`, bot.name, botLine(bot), () => onBot(index),
            () => onChange({ ...sheet, bots: bots.filter((_, i) => i !== index) })))}
        </ul>
        <Button type="button" size="sm" variant="outline" className="h-8 gap-1.5 rounded-full" onClick={() => onBot(null)}><Plus />New bot</Button>
      </section>
    </div>
  </div>
}

/** A test's value, with the input of its column's type. */
function TestValue({ id, column, value, onChange }: {
  id: string
  column: GridColumn | undefined
  value: GridCondition['value']
  onChange: (value: GridCondition['value']) => void
}) {
  const type = column?.type
  if (type === 'enum' || type === 'yesno') {
    const options: [string, string][] = type === 'yesno' ? [['true', 'Yes'], ['false', 'No']] : (column?.options ?? []).map((option) => [option, option])
    return <select id={id} aria-label="Value" className={projectInputClass} value={value === undefined ? '' : String(value)}
      onChange={(event) => onChange(event.target.value === '' ? undefined : type === 'yesno' ? event.target.value === 'true' : event.target.value)}>
      <option value="">Pick one</option>
      {options.map(([option, label]) => <option key={option} value={option}>{label}</option>)}
    </select>
  }
  const number = type !== undefined && NUMBERS.includes(type)
  const date = type !== undefined && DATES.includes(type)
  return <input id={id} aria-label="Value" className={projectInputClass} type={number ? 'number' : date ? 'date' : 'text'} value={shownValue(type, value)}
    placeholder={type === 'percent' ? '%' : undefined} {...(number ? { step: 'any', inputMode: 'decimal' as const } : {})}
    onChange={(event) => {
      const typed = event.target.value
      onChange(!typed ? undefined : !number ? typed : Number(typed) / (type === 'percent' ? 100 : 1))
    }} />
}

/** Tests a row must all pass: each a column, a comparison and a value. */
function Tests({ columns, where, onChange }: { columns: readonly GridColumn[]; where: readonly GridCondition[]; onChange: (where: GridCondition[]) => void }) {
  const id = useId()
  const set = (index: number, test: GridCondition) => onChange(where.map((each, i) => (i === index ? test : each)))
  const first = columns[0]?.name ?? ''

  return <fieldset className="space-y-2">
    <legend className="mb-1 text-[12px] font-medium text-muted-foreground">Rows where</legend>
    {where.map((test, index) => {
      const column = columnOf(columns, test.column)
      const allowed = comparisons(column?.type)
      return <div key={index} className={cn(CARD, 'space-y-2 p-2')}>
        <div className="flex items-center gap-1">
          <select aria-label="Column" className={projectInputClass} value={column?.name ?? test.column}
            onChange={(event) => set(index, { column: event.target.value, op: '=' })}>
            {!column && <option value={test.column}>{test.column} (not in this sheet)</option>}
            {columns.map((each) => <option key={each.name} value={each.name}>{each.name}</option>)}
          </select>
          <button type="button" aria-label="Remove this test" className={ICON} onClick={() => onChange(where.filter((_, i) => i !== index))}><X /></button>
        </div>
        <div className="flex gap-2">
          <select aria-label="Comparison" className={projectInputClass} value={test.op}
            onChange={(event) => {
              const op = allowed.find(([each]) => each === event.target.value)?.[0]
              if (op) set(index, TAKES_NO_VALUE.includes(op) ? { column: test.column, op } : { ...test, op })
            }}>
            {allowed.map(([op, said]) => <option key={op} value={op}>{said}</option>)}
          </select>
          {!TAKES_NO_VALUE.includes(test.op) && (
            <TestValue id={`${id}-${index}`} column={column} value={test.value}
              onChange={(value) => set(index, value === undefined ? { column: test.column, op: test.op } : { ...test, value })} />
          )}
        </div>
      </div>
    })}
    <Button type="button" size="sm" variant="outline" className="h-8 gap-1.5 rounded-full" onClick={() => onChange([...where, { column: first, op: '=' }])}>
      <Plus />{where.length === 0 ? 'Add a test' : 'And'}
    </Button>
  </fieldset>
}

/** A form over the whole screen: its bar with Cancel and Save, and its fields. */
function Form({ title, error, onSave, children }: { title: string; error: string; onSave: () => void; children: ReactNode }) {
  const save = (event: FormEvent) => {
    event.preventDefault()
    onSave()
  }
  return <form role="dialog" aria-modal="true" aria-label={title} className={STEP} onSubmit={save}>
    <div className={BAR}>
      <button type="button" aria-label="Cancel" className={ICON} onClick={() => window.history.back()}><ArrowLeft /></button>
      <p className="min-w-0 flex-1 truncate text-sm font-semibold">{title}</p>
      {error && <p role="alert" className="min-w-0 truncate text-[12px] text-red-600 dark:text-red-400">{error}</p>}
      <Button type="submit" size="sm" className="mr-1 h-8 rounded-full">Save</Button>
    </div>
    <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-4">{children}</div>
  </form>
}

/** A slice: its name and its tests. */
export function SliceForm({ columns, slices, index, onSave }: {
  columns: readonly GridColumn[]
  slices: readonly GridSlice[]
  /** The slice being changed, or null for a new one. */
  index: number | null
  onSave: (slice: GridSlice) => void
}) {
  const id = useId()
  const editing = index === null ? undefined : slices[index]
  const [name, setName] = useState(editing?.name ?? '')
  const [where, setWhere] = useState<GridCondition[]>(editing?.where ?? [{ column: columns[0]?.name ?? '', op: '=' }])
  const [error, setError] = useState('')
  const save = () => {
    const named = name.trim().slice(0, 60).trim()
    if (!named) return setError('Give the slice a name.')
    if (slices.some((each, i) => i !== index && each.name.toLowerCase() === named.toLowerCase())) return setError('Another slice has that name.')
    if (where.some(missing)) return setError('Give every test a value.')
    onSave({ name: named, where })
  }

  return <Form title={editing ? 'Edit slice' : 'New slice'} error={error} onSave={save}>
    <div>
      <label htmlFor={id} className="mb-1 block text-[12px] font-medium text-muted-foreground">Name</label>
      <input id={id} className={projectInputClass} value={name} maxLength={60} onChange={(event) => setName(event.target.value)} />
    </div>
    <Tests columns={columns} where={where} onChange={setWhere} />
  </Form>
}

/** A color rule: its tests, its color, bold, and the columns it colors. */
export function RuleForm({ columns, rules, index, onSave }: {
  columns: readonly GridColumn[]
  rules: readonly GridRule[]
  /** The rule being changed, or null for a new one. */
  index: number | null
  onSave: (rule: GridRule) => void
}) {
  const id = useId()
  const editing = index === null ? undefined : rules[index]
  const [where, setWhere] = useState<GridCondition[]>(editing?.where ?? [{ column: columns[0]?.name ?? '', op: '=' }])
  const [color, setColor] = useState<RuleColor>(editing?.color ?? 'red')
  const [bold, setBold] = useState(editing?.bold ?? false)
  const [formatted, setFormatted] = useState<string[]>(editing?.columns ?? [])
  const [error, setError] = useState('')
  const save = () => {
    if (where.some(missing)) return setError('Give every test a value.')
    onSave({ where, columns: formatted, color, ...(bold ? { bold: true } : {}) })
  }
  const toggle = (name: string) => setFormatted((all) => (all.includes(name) ? all.filter((each) => each !== name) : [...all, name]))
  const chip = (pressed: boolean, label: string, onClick: () => void) => (
    <button key={label} type="button" aria-pressed={pressed} onClick={onClick}
      className={cn('h-8 rounded-full border px-3 text-[12.5px] font-medium transition-colors duration-150',
        pressed
          ? 'border-blurple-600 bg-blurple-600 text-white dark:border-blurple-400 dark:bg-blurple-400 dark:text-ink'
          : 'border-surface-200 hover:border-surface-300 dark:border-surface-800 dark:hover:border-surface-700')}>
      {label}
    </button>
  )

  return <Form title={editing ? 'Edit color rule' : 'New color rule'} error={error} onSave={save}>
    <Tests columns={columns} where={where} onChange={setWhere} />
    <div>
      <p className="mb-1 text-[12px] font-medium text-muted-foreground">Color</p>
      <div role="radiogroup" aria-label="Color" className="flex flex-wrap gap-1">
        {RULE_COLORS.map((each) => (
          <button key={each} type="button" role="radio" aria-checked={each === color} aria-label={each} onClick={() => setColor(each)}
            className={cn('flex size-10 items-center justify-center rounded-full border-2', TONES[each], each === color ? 'border-current' : 'border-transparent')}>
            <span className="size-6 rounded-full bg-current" />
          </button>
        ))}
      </div>
    </div>
    <div className="flex items-center justify-between gap-3">
      <label htmlFor={id} className={cn('text-[14px] font-bold', TONES[color])}>Bold</label>
      <Switch id={id} checked={bold} onCheckedChange={setBold} />
    </div>
    <div>
      <p className="mb-1 text-[12px] font-medium text-muted-foreground">Columns it colors</p>
      <div className="flex flex-wrap gap-1.5">
        {chip(formatted.length === 0, 'Every column', () => setFormatted([]))}
        {columns.map((column) => chip(formatted.includes(column.name), column.name, () => toggle(column.name)))}
      </div>
    </div>
  </Form>
}

/** A bot: its name, when it runs, the rows it hands Ren, and what Ren does with them. */
export function BotForm({ slices, bots, index, onSave }: {
  slices: readonly GridSlice[]
  bots: readonly GridBot[]
  /** The bot being changed, or null for a new one. */
  index: number | null
  onSave: (bot: GridBot) => void
}) {
  const id = useId()
  const editing = index === null ? undefined : bots[index]
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
  const [name, setName] = useState(editing?.name ?? '')
  const [when, setWhen] = useState<When>(editing?.schedule?.every ?? editing?.on ?? 'added')
  // A schedule is kept in the time zone of whoever saves it last, as a scheduled agent's is.
  const [cadence, setCadence] = useState<AgentCadence>({ ...(editing?.schedule ?? defaultCadence(zone)), timezone: zone })
  const [slice, setSlice] = useState(editing?.slice ?? '')
  const [ask, setAsk] = useState(editing?.ask ?? '')
  const [error, setError] = useState('')
  const patch = (next: Partial<AgentCadence>) => setCadence((current) => ({ ...current, ...next }))
  const save = () => {
    const named = name.trim().slice(0, 60).trim()
    if (!named) return setError('Give the bot a name.')
    if (bots.some((each, i) => i !== index && each.name.toLowerCase() === named.toLowerCase())) return setError('Another bot has that name.')
    if (!ask.trim()) return setError('Say what Ren does with the rows.')
    onSave({ name: named, ask: ask.trim().slice(0, 2000), ...(isEvent(when) ? { on: when } : { schedule: { ...cadence, every: when } }), ...(slice ? { slice } : {}) })
  }
  const label = 'mb-1 block text-[12px] font-medium text-muted-foreground'

  return <Form title={editing ? 'Edit bot' : 'New bot'} error={error} onSave={save}>
    <div>
      <label htmlFor={`${id}-name`} className={label}>Name</label>
      <input id={`${id}-name`} className={projectInputClass} value={name} maxLength={60} onChange={(event) => setName(event.target.value)} />
    </div>
    <div className="space-y-2">
      <label htmlFor={`${id}-when`} className={label}>When it runs</label>
      <select id={`${id}-when`} className={projectInputClass} value={when}
        onChange={(event) => {
          const picked = WHEN.find(([each]) => each === event.target.value)?.[0]
          if (picked) setWhen(picked)
        }}>
        {WHEN.map(([each, said]) => <option key={each} value={each}>{said}</option>)}
      </select>
      {when === 'hours' && (
        <input type="number" aria-label="Hours between runs" className={projectInputClass} min={1} max={168} value={cadence.hours}
          onChange={(event) => patch({ hours: Math.min(168, Math.max(1, Number(event.target.value) || 1)) })} />
      )}
      {when === 'week' && (
        <select aria-label="Day of the week" className={projectInputClass} value={cadence.weekday} onChange={(event) => patch({ weekday: Number(event.target.value) })}>
          {WEEKDAYS.map((day, each) => <option key={day} value={each}>{day}</option>)}
        </select>
      )}
      {(when === 'day' || when === 'weekdays' || when === 'week') && (
        <input type="time" aria-label="Time of day" required className={projectInputClass} value={cadence.time} onChange={(event) => patch({ time: event.target.value })} />
      )}
      <p className="text-[12px] text-muted-foreground">
        {isEvent(when) ? 'Ren gets the rows when the page saves, after you close the spreadsheet.' : `Your time zone: ${cadence.timezone}`}
      </p>
    </div>
    <div>
      <label htmlFor={`${id}-rows`} className={label}>Rows</label>
      <select id={`${id}-rows`} className={projectInputClass} value={slice} onChange={(event) => setSlice(event.target.value)}>
        <option value="">Every row</option>
        {slice && !slices.some((each) => each.name === slice) && <option value={slice}>{slice} (not in this sheet)</option>}
        {slices.map((each) => <option key={each.name} value={each.name}>Slice {each.name}</option>)}
      </select>
    </div>
    <div>
      <label htmlFor={`${id}-ask`} className={label}>What Ren does with them</label>
      <Textarea id={`${id}-ask`} rows={4} maxLength={2000} value={ask} placeholder="Find tickets for each new plan, with prices and links."
        onChange={(event) => setAsk(event.target.value)} />
    </div>
  </Form>
}
