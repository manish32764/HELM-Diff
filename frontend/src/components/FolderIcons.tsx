export function FolderIcon({ tone }: { tone: 'green' | 'yellow' | 'gray' }) {
  const colors = {
    green: { fill: '#c9ecd4', stroke: '#2f9e5a' },
    yellow: { fill: '#ffe49a', stroke: '#c99400' },
    gray: { fill: '#ececf0', stroke: '#aeaeb2' },
  }[tone]
  return (
    <svg className="tree-icon" width="18" height="16" viewBox="0 0 18 16" aria-hidden="true">
      <path d="M1.5 3.5A1.5 1.5 0 0 1 3 2h3.6l1.6 1.8H15a1.5 1.5 0 0 1 1.5 1.5v7.7A1.5 1.5 0 0 1 15 14.5H3A1.5 1.5 0 0 1 1.5 13z"
        fill={colors.fill} stroke={colors.stroke} strokeWidth="1.2" />
    </svg>
  )
}

export function FileIcon() {
  return (
    <svg className="tree-icon" width="14" height="16" viewBox="0 0 14 16" aria-hidden="true">
      <path d="M2.5 1h6l3.5 3.5V14a1 1 0 0 1-1 1h-8.5a1 1 0 0 1-1-1V2a1 1 0 0 1 1-1z" fill="#fff" stroke="#9a9aa0" strokeWidth="1.1" />
      <path d="M8.5 1v3.5H12" fill="none" stroke="#9a9aa0" strokeWidth="1.1" />
    </svg>
  )
}
