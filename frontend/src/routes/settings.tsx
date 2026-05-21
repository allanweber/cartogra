import { createFileRoute } from '@tanstack/react-router'
import { Moon, Sun } from 'lucide-react'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { useThemeStore } from '#/stores/useThemeStore'
import { cn } from '#/lib/utils'

export const Route = createFileRoute('/settings')({
  component: SettingsPage,
})

function SettingsPage() {
  const { theme, setTheme } = useThemeStore()

  return (
    <AppLayout title="Settings" description="Account and workspace preferences">
      <div className="mx-auto max-w-2xl space-y-4">
        {/* Account */}
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Account</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center gap-3">
              <div className="flex size-12 items-center justify-center rounded-full bg-gradient-to-br from-violet-500 to-purple-700 text-base font-bold text-white">
                AW
              </div>
              <div>
                <p className="font-semibold">Allan Weber</p>
                <p className="text-sm text-muted-foreground">a.cassianoweber@gmail.com</p>
              </div>
              <Badge className="ml-auto">Admin</Badge>
            </div>
          </CardContent>
        </Card>

        {/* Workspace */}
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Workspace</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex items-center justify-between rounded-lg bg-muted/50 px-4 py-3">
              <div>
                <p className="text-sm font-medium">Tenant</p>
                <p className="text-xs text-muted-foreground">Acme Fintech</p>
              </div>
              <Badge variant="secondary">Active</Badge>
            </div>
            <div className="flex items-center justify-between rounded-lg bg-muted/50 px-4 py-3">
              <div>
                <p className="text-sm font-medium">Plan</p>
                <p className="text-xs text-muted-foreground">Phase 0 Alpha</p>
              </div>
              <Badge variant="outline">Alpha</Badge>
            </div>
          </CardContent>
        </Card>

        {/* Appearance */}
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
