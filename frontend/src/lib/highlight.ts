/**
 * Lightweight YAML + Helm (Go template) highlighter using VS Code "Light+" colours.
 * Works line by line so every line can be rendered and highlighted independently.
 */
export type Mode = 'yaml' | 'template' | 'plain'

export interface Token {
  text: string
  cls: string
}

const TEMPLATE_KEYWORDS = new Set(['if', 'else', 'end', 'range', 'with', 'define', 'block', 'template', 'and', 'or',
  'not', 'eq', 'ne', 'lt', 'le', 'gt', 'ge', 'nil', 'true', 'false'])

export function modeFor(fileName: string): Mode {
  const n = fileName.toLowerCase()
  if (n.endsWith('.yaml') || n.endsWith('.yml')) return 'yaml'
  if (n.endsWith('.tpl') || n.endsWith('.txt') || n.endsWith('.gotmpl')) return 'template'
  return 'plain'
}

export function highlight(line: string, mode: Mode): Token[] {
  if (line.length === 0) return []
  const cls: string[] = new Array(line.length).fill('')
  if (mode === 'yaml') yamlPass(line, cls)
  if (line.includes('{{')) templatePass(line, cls)
  const tokens: Token[] = []
  let start = 0
  for (let i = 1; i <= line.length; i++) {
    if (i === line.length || cls[i] !== cls[start]) {
      tokens.push({ text: line.slice(start, i), cls: cls[start] })
      start = i
    }
  }
  return tokens
}

function fill(cls: string[], from: number, to: number, c: string) {
  for (let i = Math.max(0, from); i < to && i < cls.length; i++) cls[i] = c
}

const KEY = /^("(?:[^"\\]|\\.)*"|'[^']*'|[^\s#'"[\]{}][^#]*?)(\s*)(:)(?=\s|$)/

function yamlPass(line: string, cls: string[]) {
  const trimmed = line.trimStart()
  const indent = line.length - trimmed.length
  if (!trimmed) return
  if (trimmed.startsWith('#')) return fill(cls, indent, line.length, 'tk-comment')
  if (/^(---|\.\.\.)\s*$/.test(trimmed)) return fill(cls, indent, line.length, 'tk-doc')

  let pos = indent
  while (line[pos] === '-' && (pos + 1 === line.length || line[pos + 1] === ' ')) {
    fill(cls, pos, pos + 1, 'tk-punct')
    pos++
    while (line[pos] === ' ') pos++
  }
  const rest = line.slice(pos)
  const key = rest.startsWith('{{') ? null : KEY.exec(rest)
  if (key) {
    fill(cls, pos, pos + key[1].length, 'tk-key')
    const colon = pos + key[1].length + key[2].length
    fill(cls, colon, colon + 1, 'tk-punct')
    pos = colon + 1
  }

  let quote: string | null = null
  let commentAt = -1
  for (let i = pos; i < line.length; i++) {
    const ch = line[i]
    if (quote) {
      if (ch === quote) quote = null
      continue
    }
    if ((ch === '"' || ch === "'") && (i === pos || /\s/.test(line[i - 1]))) {
      quote = ch
      continue
    }
    if (ch === '#' && i > 0 && /\s/.test(line[i - 1])) {
      commentAt = i
      break
    }
  }
  const valueEnd = commentAt >= 0 ? commentAt : line.length
  if (commentAt >= 0) fill(cls, commentAt, line.length, 'tk-comment')
  const raw = line.slice(pos, valueEnd)
  const value = raw.trim()
  if (!value) return
  const vs = pos + raw.indexOf(value)
  let c = 'tk-plain'
  if (/^(["']).*\1$/.test(value)) c = 'tk-str'
  else if (/^[-+]?(\d+(\.\d+)?|\.\d+)([eE][-+]?\d+)?$/.test(value) || /^0x[0-9a-f]+$/i.test(value)) c = 'tk-num'
  else if (/^(true|false|null|yes|no|on|off|~)$/i.test(value)) c = 'tk-bool'
  else if (/^[|>][-+0-9]*$/.test(value)) c = 'tk-punct'
  else if (/^[&*]\S+/.test(value)) c = 'tk-anchor'
  else if ((value.startsWith('{') && !value.startsWith('{{')) || value.startsWith('[')) c = 'tk-flow'
  fill(cls, vs, vs + value.length, c)
}

const ACTION = /\{\{[\s\S]*?\}\}/g
const INNER = /("(?:[^"\\]|\\.)*"|`[^`]*`)|(\$\w*(?:\.\w+)*|\.[\w.]*)|(\b\d+(?:\.\d+)?\b)|([A-Za-z_]\w*)|(\||:=|=|\(|\))/g

function templatePass(line: string, cls: string[]) {
  const ranges: [number, number][] = []
  ACTION.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = ACTION.exec(line))) ranges.push([m.index, m.index + m[0].length])
  const lastOpen = line.lastIndexOf('{{')
  if (lastOpen >= 0 && !ranges.some(([s, e]) => lastOpen >= s && lastOpen < e)) ranges.push([lastOpen, line.length])

  for (const [s, e] of ranges) {
    const body = line.slice(s, e)
    if (/^\{\{-?\s*\/\*/.test(body)) {
      fill(cls, s, e, 'tk-comment')
      continue
    }
    fill(cls, s, e, 'tk-tpl')
    const open = /^\{\{-?/.exec(body)![0].length
    fill(cls, s, s + open, 'tk-tpl-delim')
    const closeMatch = /-?\}\}$/.exec(body)
    const close = closeMatch ? closeMatch[0].length : 0
    if (close) fill(cls, e - close, e, 'tk-tpl-delim')
    const inner = body.slice(open, body.length - close)
    const base = s + open
    INNER.lastIndex = 0
    let t: RegExpExecArray | null
    while ((t = INNER.exec(inner))) {
      const a = base + t.index
      const c = t[1] ? 'tk-tpl-str' : t[2] ? 'tk-tpl-var' : t[3] ? 'tk-tpl-num'
        : t[4] ? (TEMPLATE_KEYWORDS.has(t[4]) ? 'tk-tpl-kw' : 'tk-tpl-fn') : 'tk-tpl-op'
      fill(cls, a, a + t[0].length, c)
    }
  }
}
