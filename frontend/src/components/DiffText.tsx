/** Highlights the part of `value` that differs from `against`; the common prefix and suffix stay plain. */
export function DiffText({ value, against }: { value: string; against: string }) {
  let start = 0
  while (start < value.length && start < against.length && value[start] === against[start]) start++
  let end = 0
  while (end < value.length - start && end < against.length - start
    && value[value.length - 1 - end] === against[against.length - 1 - end]) end++
  const mid = value.slice(start, value.length - end)
  return <>{value.slice(0, start)}{mid && <mark className="diff-mark">{mid}</mark>}{value.slice(value.length - end)}</>
}
