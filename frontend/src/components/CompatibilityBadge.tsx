import { TriangleAlert } from 'lucide-react'

import { Badge } from '#/components/ui/badge'

/**
 * The breaking-change verdict of a contract version. Renders nothing for an unchecked version
 * (`breaking: null`, stored before 5.3). Breaking uses the critical color and icon of `SpofBadge`.
 */
export function CompatibilityBadge({ breaking }: { breaking: boolean | null }) {
  if (breaking === null) return null
  if (breaking) {
    return (
      <Badge variant="outline" className="border-current text-critical">
        <TriangleAlert aria-hidden="true" />
        Breaking
      </Badge>
    )
  }
  return (
    <Badge variant="outline" className="text-muted-foreground">
      Compatible
    </Badge>
  )
}
