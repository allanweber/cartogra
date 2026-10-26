export function DriftLegend() {
  return (
    <ul aria-label="Drift legend" className="flex items-center gap-3 text-xs text-muted-foreground">
      <li className="flex items-center gap-1.5">
        <svg width="20" height="6" aria-hidden="true">
          <line x1="0" y1="3" x2="20" y2="3" className="stroke-warning" strokeWidth="2.5" />
        </svg>
        Undeclared
      </li>
      <li className="flex items-center gap-1.5">
        <svg width="20" height="6" aria-hidden="true">
          <line x1="0" y1="3" x2="20" y2="3" className="stroke-info" strokeWidth="2.5" strokeDasharray="6 4" />
        </svg>
        Missing
      </li>
    </ul>
  )
}
