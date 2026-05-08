import { ArrowRight, Sparkles } from 'lucide-react'

import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'

interface PhasePlaceholderProps {
  summary: string
  capabilities: string[]
  deliveryPhase: string
}

export function PhasePlaceholder({
  summary,
  capabilities,
  deliveryPhase,
}: PhasePlaceholderProps) {
  return (
    <div className="grid gap-5 xl:grid-cols-[minmax(0,1.3fr)_minmax(20rem,0.9fr)]">
      <Card className="border-border/70 bg-background/85">
        <CardHeader className="space-y-3">
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Sparkles className="size-4 text-primary" aria-hidden="true" />
            <span>Why this surface exists</span>
          </div>
          <CardTitle className="text-xl tracking-[-0.02em]">Decision support, not empty chrome</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="max-w-2xl text-sm leading-7 text-muted-foreground">{summary}</p>
        </CardContent>
      </Card>

      <Card className="border-border/70 bg-card/95">
        <CardHeader>
          <CardTitle className="text-base font-semibold tracking-[-0.01em]">
            Targeted in {deliveryPhase}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <ul className="space-y-3 text-sm text-muted-foreground">
            {capabilities.map((capability) => (
              <li key={capability} className="flex gap-3 leading-6">
                <ArrowRight className="mt-1 size-4 shrink-0 text-primary" aria-hidden="true" />
                <span>{capability}</span>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>
    </div>
  )
}