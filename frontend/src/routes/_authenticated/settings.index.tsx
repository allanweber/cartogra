import { createFileRoute, Link } from '@tanstack/react-router'
import { GitBranch, Moon, Sun, User } from 'lucide-react'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '#/components/ui/card'
import { useCurrentTenant } from '#/stores/useTenantStore'
import { useThemeStore } from '#/stores/useThemeStore'
import { cn } from '#/lib/utils'

export const Route = createFileRoute('/_authenticated/settings/')({
  component: SettingsIndexPage,
})

function SettingsIndexPage() {
  const { theme, setTheme } = useThemeStore()
  const currentTenant = useCurrentTenant()

  return (
    <AppLayout title="Settings" description="Account and workspace preferences">
      <div className="mx-auto max-w-2xl space-y-4">
        <div className="grid grid-cols-2 gap-3">
          <Link to="/settings/profile" className="block">
            <Card className="h-full cursor-pointer transition-colors hover:bg-muted/50">
              <CardHeader className="pb-2">
                <div className="flex items-center gap-2">
                  <User className="size-4 text-muted-foreground" />
                  <CardTitle className="text-sm">Profile</CardTitle>
                </div>
              </CardHeader>
              <CardContent>
                <CardDescription className="text-xs">Name, email, and password</CardDescription>
              </CardContent>
            </Card>
          </Link>

          <Card className="cursor-not-allowed opacity-60">
            <CardHeader className="pb-2">
              <div className="flex items-center gap-2">
                <GitBranch className="size-4 text-muted-foreground" />
                <CardTitle className="text-sm">SCM Connections</CardTitle>
                <Badge variant="outline" className="ml-auto text-xs">Coming soon</Badge>
              </div>
            </CardHeader>
            <CardContent>
              <CardDescription className="text-xs">GitHub, GitLab, Bitbucket</CardDescription>
            </CardContent>
          </Card>
        </div>

        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Workspace</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex items-center justify-between rounded-lg bg-muted/50 px-4 py-3">
              <div>
                <p className="text-sm font-medium">Workspace</p>
                <p className="text-xs text-muted-foreground">{currentTenant.name}</p>
              </div>
            </div>
            <div className="flex items-center justify-between rounded-lg bg-muted/50 px-4 py-3">
              <div>
                <p className="text-sm font-medium">Plan</p>
                <p className="text-xs text-muted-foreground">{currentTenant.plan}</p>
              </div>
              <Badge variant="outline">{currentTenant.plan}</Badge>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Appearance</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-3">
              <ThemeButton
                label="Light"
                icon={<Sun className="size-4" />}
                active={theme === 'light'}
                onClick={() => setTheme('light')}
              />
              <ThemeButton
                label="Dark"
                icon={<Moon className="size-4" />}
                active={theme === 'dark'}
                onClick={() => setTheme('dark')}
              />
            </div>
          </CardContent>
        </Card>
      </div>
    </AppLayout>
  )
}

function ThemeButton({
  label,
  icon,
  active,
  onClick,
}: {
  label: string
  icon: React.ReactNode
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'flex flex-col items-center gap-2 rounded-xl border p-4 text-sm font-medium transition-all',
        active
          ? 'border-primary bg-primary/5 text-primary'
          : 'border-border bg-background text-muted-foreground hover:bg-muted',
      )}
    >
      {icon}
      {label}
    </button>
  )
}
