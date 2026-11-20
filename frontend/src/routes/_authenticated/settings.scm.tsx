import { createFileRoute } from '@tanstack/react-router'

import { SettingsTabsLayout } from '#/components/SettingsTabsLayout'
import { SpecPathsCard } from '#/components/SpecPathsCard'

export const Route = createFileRoute('/_authenticated/settings/scm')({
  component: ScmConfigurationPage,
})

function ScmConfigurationPage() {
  return (
    <SettingsTabsLayout>
      <div className="space-y-2">
        <div className="mb-1">
          <h2 className="text-sm font-semibold">SCM Configuration</h2>
          <p className="text-xs text-muted-foreground">
            Configure how Cartogra reads your repositories on each SCM sync.
          </p>
        </div>
        <SpecPathsCard />
      </div>
    </SettingsTabsLayout>
  )
}
