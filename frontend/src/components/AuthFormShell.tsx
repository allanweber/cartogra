import { useState, useEffect } from 'react'
import { Skeleton } from '#/components/ui/skeleton'

interface AuthFormShellProps {
  children: React.ReactNode
}

export function AuthFormShell({ children }: AuthFormShellProps) {
  const [mounted, setMounted] = useState(false)

  useEffect(() => {
    setMounted(true)
  }, [])

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6">
          <h1 className="text-2xl font-semibold tracking-tight">Cartogra</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Service registry &amp; dependency intelligence
          </p>
        </div>

        <div className="rounded-2xl border border-border bg-card p-6 shadow-sm">
          {mounted ? children : <AuthCardSkeleton />}
        </div>
      </div>
    </div>
  )
}

function AuthCardSkeleton() {
  return (
    <div className="space-y-5">
      <div className="space-y-1.5">
        <Skeleton className="h-5 w-44" />
        <Skeleton className="h-4 w-56" />
      </div>
      <div className="space-y-4">
        <div className="space-y-1.5">
          <Skeleton className="h-4 w-14" />
          <Skeleton className="h-10 w-full" />
        </div>
        <div className="space-y-1.5">
          <Skeleton className="h-4 w-16" />
          <Skeleton className="h-10 w-full" />
        </div>
        <Skeleton className="h-10 w-full" />
      </div>
    </div>
  )
}
