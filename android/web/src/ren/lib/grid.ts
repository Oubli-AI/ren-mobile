/**
 * A grid: a spreadsheet as agents write it and read it back, and what Squad's DataGrid outcome saves as grid.json
 * (`agent/api/core/grid.py` is the server's side). A cell is a number, a boolean, text, or a formula (text that
 * starts with "="); `formats` gives a column's number format by its letter, as Excel writes one ("#,##0",
 * "$#,##0.00", "0.0%", "yyyy-mm-dd").
 *
 * Pure module (type-only imports) so `node --experimental-strip-types --test` runs it and the spreadsheet engine
 * stays in its own chunk.
 */
import type { ICellData, IWorkbookData, IWorksheetData } from '@univerjs/core'

export type GridCell = string | number | boolean | null
export interface GridSheet { name: string; rows: GridCell[][]; formats?: Record<string, string> }
export interface Grid { title: string; sheets: GridSheet[] }

/** What a grid shows, sheet by sheet: each cell as the spreadsheet displays it. */
export interface GridDisplaySheet { name: string; rows: string[][] }

/**
 * A grid in a page: the grid, what its cells show once a spreadsheet has calculated it, and the workbook the
 * spreadsheet keeps (styles, widths, filters) once it has been changed there.
 */
export interface GridValue { grid: Grid; display: GridDisplaySheet[] | null; workbook: IWorkbookData | null }

/**
 * A sheet as the spreadsheet reads it back, cell by cell: its formula ("" without one), its value (a formula's
 * result), its number format ("" without one) and what it shows.
 */
export interface SheetReading { name: string; formulas: string[][]; values: unknown[][]; formats: string[][]; display: string[][] }

export const GRID_FENCE = 'grid'

/** How agents are told to write a spreadsheet (`GRID_MARKDOWN` on the server, word for word). */
export const GRID_RULE = 'A spreadsheet is one ```grid fence holding JSON: {"title": ..., "sheets": [{"name": ..., "rows": [[...], ...], "formats": {"B": "#,##0"}}]}. The first row of a sheet is its header; a cell is a number, true or false, text, or a formula as text starting with "=" in A1 notation ("=SUM(B2:B9)", "=Sheet2!B4*0.2"); "formats" is optional and gives a column\'s Excel number format by its letter. Use it for data to sort, filter and calculate with; a small table of text stays a Markdown table.'

/** Spare rows and columns below and beside the data, so there is room to add to a sheet. */
const SPARE_ROWS = 50
const SPARE_COLUMNS = 6
const MAX_COLUMN_WIDTH = 320

const text = (value: unknown): string => (typeof value === 'string' ? value : '')
const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
/** Whether a cell is a formula: text that starts with "=". */
export const isFormula = (value: unknown): value is string => typeof value === 'string' && value.startsWith('=')

/** "A" for 0, "Z" for 25, "AA" for 26. */
export function columnLetter(index: number): string {
  let letters = ''
  for (let rest = index; rest >= 0; rest = Math.floor(rest / 26) - 1) letters = String.fromCharCode(65 + (rest % 26)) + letters
  return letters
}

function cell(value: unknown): GridCell {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  return typeof value === 'string' || typeof value === 'boolean' ? value : null
}

const titled = (title: string): string => title.trim().slice(0, 120) || 'Spreadsheet'

/**
 * A grid from JSON anyone wrote: every sheet with a name of its own that Excel accepts and rows of plain cells; null
 * when it holds no sheet.
 */
export function normalizeGrid(value: unknown): Grid | null {
  if (!isRecord(value) || !Array.isArray(value["sheets"])) return null
  const sheets: GridSheet[] = []
  const taken = new Set<string>()
  for (const sheet of value["sheets"]) {
    if (!isRecord(sheet)) continue
    let name = text(sheet["name"]).replace(/[[\]:*?/\\\s]+/g, ' ').trim().slice(0, 31).trim()
    for (let number = sheets.length + 1; !name || taken.has(name.toLowerCase()); number += 1) name = `Sheet${number}`
    taken.add(name.toLowerCase())
    const formats = isRecord(sheet["formats"])
      ? Object.fromEntries(Object.entries(sheet["formats"]).filter((entry): entry is [string, string] => /^[A-Z]+$/.test(entry[0]) && typeof entry[1] === 'string' && !!entry[1]))
      : {}
    sheets.push({
      name,
      rows: Array.isArray(sheet["rows"]) ? sheet["rows"].filter(Array.isArray).map((row: unknown[]) => row.map(cell)) : [],
      ...(Object.keys(formats).length > 0 ? { formats } : {}),
    })
  }
  return sheets.length > 0 ? { title: titled(text(value["title"])), sheets } : null
}

/** The grid a ```grid fence, a grid block's `data-grid` or a run's grid.json holds, or null when it isn't one. */
export function gridFromJson(json: string): Grid | null {
  try {
    return normalizeGrid(JSON.parse(json))
  } catch {
    return null
  }
}

/** A grid as its ```grid fence: a row a line, so a sheet reads as a table. */
export function gridMarkdown(grid: Grid): string {
  const sheets = grid.sheets.map((sheet) => {
    const rows = sheet.rows.map((row) => `      ${JSON.stringify(row)}`).join(',\n')
    const formats = sheet.formats ? `, "formats": ${JSON.stringify(sheet.formats)}` : ''
    return `    {"name": ${JSON.stringify(sheet.name)}${formats}, "rows": [\n${rows}\n    ]}`
  }).join(',\n')
  return `\`\`\`${GRID_FENCE}\n{"title": ${JSON.stringify(grid.title)}, "sheets": [\n${sheets}\n]}\n\`\`\``
}

/** A new grid with a header row to type over. */
export function starterGrid(): Grid {
  return { title: 'Spreadsheet', sheets: [{ name: 'Sheet1', rows: [['Name', 'Amount', 'Notes']] }] }
}

/**
 * The workbook a grid opens as: each sheet's cells, its header row bold and frozen, a column's number format on its
 * data cells and each column as wide as its longest text: what its cells show (`display`) once a spreadsheet has
 * calculated them, what they hold before.
 */
export function gridWorkbook(grid: Grid, display?: readonly GridDisplaySheet[] | null): Partial<IWorkbookData> {
  const styles: NonNullable<IWorkbookData['styles']> = { header: { bl: 1 } }
  const sheets: Record<string, Partial<IWorksheetData>> = {}
  const sheetOrder = grid.sheets.map((sheet, index) => {
    const id = `sheet-${index + 1}`
    const columns = Math.max(0, ...sheet.rows.map((row) => row.length))
    const cellData: Record<number, Record<number, ICellData>> = {}
    const widths = Array.from({ length: columns }, () => 0)
    sheet.rows.forEach((row, r) => {
      const cells: Record<number, ICellData> = {}
      row.forEach((value, c) => {
        if (value === null || value === '') return
        const format = r > 0 ? sheet.formats?.[columnLetter(c)] : undefined
        if (format) styles[`format-${format}`] = { n: { pattern: format } }
        const style = r === 0 ? { s: 'header' } : format ? { s: `format-${format}` } : {}
        cells[c] = isFormula(value) ? { f: value, ...style } : { v: value, ...style }
        widths[c] = Math.max(widths[c] ?? 0, (display?.[index]?.rows[r]?.[c] ?? String(value)).length)
      })
      cellData[r] = cells
    })
    sheets[id] = {
      id,
      name: sheet.name,
      rowCount: sheet.rows.length + SPARE_ROWS,
      columnCount: columns + SPARE_COLUMNS,
      cellData,
      freeze: { xSplit: 0, ySplit: 1, startRow: 1, startColumn: -1 },
      columnData: Object.fromEntries(widths.map((length, c) => [c, { w: Math.min(MAX_COLUMN_WIDTH, Math.max(72, length * 8 + 24)) }])),
    }
    return id
  })
  return { id: 'grid', name: grid.title, styles, sheetOrder, sheets }
}

/**
 * What the spreadsheet read back, cut to the cells in use: the grid agents read (a formula where a cell has one, a
 * column's number format where its cells share one), each cell's value by sheet, row and cell (a formula's result,
 * which an Excel file keeps beside the formula) and what each cell shows.
 */
export function readGrid(title: string, sheets: readonly SheetReading[]): { grid: Grid; values: GridCell[][][]; display: GridDisplaySheet[] } {
  const read = sheets.map((sheet) => {
    const cells = sheet.values.map((row, r) => row.map((value, c): GridCell => {
      const written = sheet.formulas[r]?.[c] || cell(value)
      return written === '' ? null : written
    }))
    const height = cells.reduce((last, row, r) => (row.some((value) => value !== null) ? r + 1 : last), 0)
    const width = Math.max(0, ...cells.map((row) => row.reduce<number>((last, value, c) => (value === null ? last : c + 1), 0)))
    const cut = <T>(pick: (r: number, c: number) => T): T[][] => Array.from({ length: height }, (_, r) => Array.from({ length: width }, (_, c) => pick(r, c)))
    const rows = cut((r, c) => cells[r]?.[c] ?? null)
    const formats: Record<string, string> = {}
    for (let c = 0; c < width; c += 1) {
      const patterns = new Set(rows.flatMap((row, r) => (r > 0 && (isFormula(row[c]) || typeof row[c] === 'number') ? [sheet.formats[r]?.[c] ?? ''] : [])))
      const [only] = patterns
      if (patterns.size === 1 && only) formats[columnLetter(c)] = only
    }
    return {
      sheet: { name: sheet.name, rows, ...(Object.keys(formats).length > 0 ? { formats } : {}) },
      values: cut((r, c) => cell(sheet.values[r]?.[c])),
      display: { name: sheet.name, rows: cut((r, c) => sheet.display[r]?.[c] ?? '') },
    }
  })
  return { grid: { title: titled(title), sheets: read.map((item) => item.sheet) }, values: read.map((item) => item.values), display: read.map((item) => item.display) }
}

const isDisplay = (value: unknown): value is GridDisplaySheet[] => Array.isArray(value) && value.every((sheet) =>
  isRecord(sheet) && typeof sheet["name"] === 'string' && Array.isArray(sheet["rows"])
  && sheet["rows"].every((row) => Array.isArray(row) && row.every((shown) => typeof shown === 'string')))

/** Only a workbook's shape is checked here: the spreadsheet reads the rest. */
const isWorkbook = (value: unknown): value is IWorkbookData => isRecord(value) && Array.isArray(value["sheetOrder"]) && isRecord(value["sheets"])

/**
 * What a grid block holds, from its node's attributes or its saved element: the grid, with what it shows and its
 * workbook when a spreadsheet wrote them for these sheets; null without a grid.
 */
export function gridValue(grid: unknown, display: unknown, workbook: unknown): GridValue | null {
  const held = normalizeGrid(grid)
  if (!held) return null
  return {
    grid: held,
    display: isDisplay(display) && display.length === held.sheets.length ? display : null,
    workbook: isWorkbook(workbook) ? workbook : null,
  }
}

/** A grid as it reads before a spreadsheet has calculated it: every cell as written, a formula as its text. */
export function gridDisplay(grid: Grid): GridDisplaySheet[] {
  return grid.sheets.map((sheet) => {
    const width = Math.max(0, ...sheet.rows.map((row) => row.length))
    const shown = (value: GridCell | undefined) => (value === null || value === undefined ? '' : typeof value === 'boolean' ? String(value).toUpperCase() : String(value))
    return { name: sheet.name, rows: sheet.rows.map((row) => Array.from({ length: width }, (_, c) => shown(row[c]))) }
  })
}

/**
 * What a column holds, after AppSheet's column types (https://support.google.com/appsheet/answer/10106435). It decides
 * how a row's card and detail show a cell, what a tap on it does and which input a form gives it.
 */
export type ColumnType =
  | 'text' | 'longtext' | 'number' | 'price' | 'percent' | 'date' | 'datetime' | 'time' | 'yesno' | 'enum'
  | 'email' | 'phone' | 'url' | 'image' | 'address' | 'latlong'

/** A sheet's column: its header, its type, and an enum's values in the order they first appear. */
export interface GridColumn { name: string; type: ColumnType; options: string[] }

const TEXT_TYPES: readonly (readonly [ColumnType, RegExp])[] = [
  ['email', /^[^\s@]+@[^\s@]+\.[^\s@]+$/],
  ['image', /^https?:\/\/\S+\.(?:png|jpe?g|gif|webp|avif|svg)(?:[?#]\S*)?$/i],
  ['url', /^https?:\/\/\S+$/i],
  ['date', /^\d{4}-\d{2}-\d{2}$/],
  ['datetime', /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(?::\d{2})?$/],
  ['time', /^\d{1,2}:\d{2}(?::\d{2})?$/],
  ['latlong', /^-?\d{1,2}(?:\.\d+)?,\s*-?\d{1,3}(?:\.\d+)?$/],
  // With its country code, or as North America writes one, the form AppSheet recognizes.
  ['phone', /^(?:\+\d[\d\s().-]{6,18}|\(?\d{3}\)?[\s.-]\d{3}[\s.-]\d{4})$/],
]

/** A column of numbers by its Excel number format: a share, an amount of money, a date or a time. */
function numberType(format: string | undefined): ColumnType {
  if (!format) return 'number'
  if (format.includes('%')) return 'percent'
  // "[$-409]" names a locale, as a date's format does; "[$€-407]" and a bare "$" are money.
  if (/[$€£¥]/.test(format.replace(/\[\$-[^\]]*\]/g, ''))) return 'price'
  // Text in quotes and brackets is not a date code.
  const codes = format.replace(/"[^"]*"|\[[^\]]*\]/g, '').toLowerCase()
  const day = /[yd]/.test(codes)
  const hour = /[hs]/.test(codes)
  return day && hour ? 'datetime' : day ? 'date' : hour ? 'time' : 'number'
}

/**
 * A sheet's columns, typed from its header and its cells the way AppSheet reads a sheet: `values` gives each cell's
 * value where a spreadsheet has calculated the sheet (a formula's result), and a column of numbers is typed by its
 * number format. A column named for an address is one, as in AppSheet.
 */
export function gridColumns(sheet: GridSheet, values?: readonly (readonly GridCell[])[]): GridColumn[] {
  const [header = [], ...rows] = sheet.rows
  const width = Math.max(0, ...sheet.rows.map((row) => row.length))
  return Array.from({ length: width }, (_, c) => {
    const name = String(header[c] ?? '').trim() || columnLetter(c)
    const held = rows.map((row, r) => (isFormula(row[c]) ? values?.[r + 1]?.[c] ?? null : row[c] ?? null)).filter((value) => value !== null && value !== '')
    const texts = held.filter((value) => typeof value === 'string')
    const distinct = [...new Set(texts)]
    const all = (type: string) => held.length > 0 && held.every((value) => typeof value === type)
    const type: ColumnType = all('boolean') ? 'yesno'
      : all('number') ? numberType(sheet.formats?.[columnLetter(c)])
      : !all('string') ? 'text'
      : TEXT_TYPES.find(([, pattern]) => texts.every((value) => pattern.test(value)))?.[0]
        ?? (/address/i.test(name) ? 'address'
          : texts.some((value) => value.length > 120 || value.includes('\n')) ? 'longtext'
          // A few values that repeat are choices.
          : distinct.length < texts.length && distinct.length <= Math.max(2, texts.length / 2) ? 'enum'
          : 'text')
    return { name, type, options: type === 'enum' ? distinct : [] }
  })
}

const DAY = 86_400_000
/** 1970-01-01 as a spreadsheet counts days, from 1899-12-30. */
const EPOCH = 25_569

/** A date, a time or both as a spreadsheet keeps one, in days with a time as a part of a day, from what an input gives ("2026-11-21", "09:30", "2026-11-21T09:30"). */
export function temporalSerial(type: 'date' | 'datetime' | 'time', text: string): number | null {
  const ms = Date.parse(type === 'time' ? `1970-01-01T${text}Z` : type === 'date' ? `${text}T00:00Z` : `${text.replace(' ', 'T')}Z`)
  return Number.isFinite(ms) ? ms / DAY + (type === 'time' ? 0 : EPOCH) : null
}

/** What an input takes for a date, a time or both that a spreadsheet keeps as a number. */
export function temporalText(type: 'date' | 'datetime' | 'time', serial: number): string {
  const iso = new Date(Math.round((type === 'time' ? serial % 1 : serial - EPOCH) * DAY)).toISOString()
  return type === 'date' ? iso.slice(0, 10) : type === 'time' ? iso.slice(11, 16) : iso.slice(0, 16)
}

/** A sheet as CSV (RFC 4180): a cell with a comma, a quote or a line break is quoted. */
export function sheetCsv(rows: readonly (readonly string[])[]): string {
  return rows.map((row) => row.map((value) => (/[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value)).join(',')).join('\r\n')
}
