import { cn } from '#/lib/utils'

import type { DiffLine } from '#/lib/contract-types'

const LEFT_TINT: Record<DiffLine['kind'], string> = {
  same: '',
  added: 'bg-muted/40',
  removed: 'bg-critical-subtle',
  changed: 'bg-critical-subtle',
}

const RIGHT_TINT: Record<DiffLine['kind'], string> = {
  same: '',
  added: 'bg-success-subtle',
  removed: 'bg-muted/40',
  changed: 'bg-success-subtle',
}

const MARKER: Record<DiffLine['kind'], [string, string]> = {
  same: [' ', ' '],
  added: [' ', '+'],
  removed: ['-', ' '],
  changed: ['-', '+'],
}

export function SideBySideDiff({ lines, fromLabel, toLabel }: { lines: DiffLine[]; fromLabel: string; toLabel: string }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full table-fixed border-collapse font-mono text-xs" aria-label={`Diff ${fromLabel} to ${toLabel}`}>
        <colgroup>
          <col className="w-10" />
          <col />
          <col className="w-10" />
          <col />
        </colgroup>
        <thead>
          <tr className="border-b border-border bg-muted/50 text-left font-sans text-xs font-semibold text-muted-foreground">
            <th colSpan={2} className="px-3 py-2">{fromLabel}</th>
            <th colSpan={2} className="border-l border-border px-3 py-2">{toLabel}</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line, i) => (
            <tr key={i} data-kind={line.kind}>
              <td className={cn('select-none px-2 text-right align-top text-muted-foreground', LEFT_TINT[line.kind])}>
                {line.leftNo}
              </td>
              <td className={cn('whitespace-pre-wrap break-all px-2 align-top', LEFT_TINT[line.kind])}>
                {line.left !== null && (
                  <>
                    <span aria-hidden className="select-none text-muted-foreground">{MARKER[line.kind][0]} </span>
                    {line.left}
                  </>
                )}
              </td>
              <td className={cn('select-none border-l border-border px-2 text-right align-top text-muted-foreground', RIGHT_TINT[line.kind])}>
                {line.rightNo}
              </td>
              <td className={cn('whitespace-pre-wrap break-all px-2 align-top', RIGHT_TINT[line.kind])}>
                {line.right !== null && (
                  <>
                    <span aria-hidden className="select-none text-muted-foreground">{MARKER[line.kind][1]} </span>
                    {line.right}
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
