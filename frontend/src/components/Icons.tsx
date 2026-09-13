const svg = {
  width: 16, height: 16, viewBox: '0 0 16 16', fill: 'none', stroke: 'currentColor',
  strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true,
} as const

/** Box with an arrow leaving it: open in a separate tab. */
export const PopOutIcon = () => (
  <svg {...svg}><path d="M9.5 2.5h4v4" /><path d="M13.5 2.5 7.5 8.5" /><path d="M12 9.5v3a1 1 0 0 1-1 1H3.5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h3" /></svg>
)

export const MaximizeIcon = () => (
  <svg {...svg}><path d="M2.5 6V2.5H6" /><path d="M10 2.5h3.5V6" /><path d="M13.5 10v3.5H10" /><path d="M6 13.5H2.5V10" /></svg>
)

export const RestoreIcon = () => (
  <svg {...svg}><path d="M6 2.5V6H2.5" /><path d="M13.5 6H10V2.5" /><path d="M10 13.5V10h3.5" /><path d="M2.5 10H6v3.5" /></svg>
)

export const ChevronUpIcon = () => <svg {...svg}><path d="m4 10 4-4 4 4" /></svg>

export const ChevronDownIcon = () => <svg {...svg}><path d="m4 6 4 4 4-4" /></svg>
