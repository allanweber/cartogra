import { cn } from '#/lib/utils'

export type UnderDevelopmentKind = 'mock' | 'placeholder'

const SUBJECT: Record<UnderDevelopmentKind, string> = {
  mock: 'This is mock data.',
  placeholder: 'This is a placeholder.',
}

interface UnderDevelopmentBannerProps {
  kind: UnderDevelopmentKind
  className?: string
}

export function UnderDevelopmentBanner({ kind, className }: UnderDevelopmentBannerProps) {
  return (
    <div
      role="status"
      className={cn(
        'flex shrink-0 items-center justify-center gap-2 border-b border-warning/30 bg-warning-subtle px-4 py-1.5 text-center text-xs font-medium text-foreground',
        className,
      )}
    >
      {SUBJECT[kind]} This feature is under development and coming live soon.
    </div>
  )
}
