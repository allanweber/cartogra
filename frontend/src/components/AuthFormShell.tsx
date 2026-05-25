import { useState, useEffect } from 'react'
import { GitBranch } from 'lucide-react'
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
      <div
        aria-hidden="true"
        className="pointer-events-none fixed inset-0 -z-10 overflow-hidden"
      >
        <div className="absolute -top-40 left-1/2 -translate-x-1/2 size-[600px] rounded-full bg-primary/8 blur-3xl" />
        <div className="absolute bottom-0 right-0 size-[400px] rounded-full bg-secondary/6 blur-3xl" />
      </div>

      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center gap-3">
          <div className="flex size-11 items-center justify-center rounded-xl bg-primary text-primary-foreground shadow-lg">
            <GitBranch className="size-5" />
          </div>
          <div className="text-center">
            <h1 className="text-xl font-bold tracking-tight">Cartogra</h1>
            <p className="mt-0.5 text-sm text-muted-foreground">
              Service registry &amp; dependency intelligence
            </p>
          </div>
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
