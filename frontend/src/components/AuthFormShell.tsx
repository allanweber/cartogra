import { Compass } from 'lucide-react'

import { Card } from '#/components/ui/card'

interface AuthFormShellProps {
  children: React.ReactNode
}

export function AuthFormShell({ children }: AuthFormShellProps) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6">
          <div className="flex items-center gap-2 mb-1">
            <Compass className="size-5 shrink-0 text-primary" aria-hidden="true" />
            <h1 className="text-2xl font-semibold tracking-tight">Cartogra</h1>
          </div>
          <p className="text-sm text-muted-foreground">
            Service registry &amp; dependency intelligence
          </p>
        </div>

        <Card className="rounded-2xl px-6 shadow-sm">{children}</Card>
      </div>
    </div>
  )
}
