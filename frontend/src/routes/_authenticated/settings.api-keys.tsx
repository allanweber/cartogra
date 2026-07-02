import { createFileRoute } from '@tanstack/react-router'

import { SettingsTabsLayout } from '#/components/SettingsTabsLayout'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'

export const Route = createFileRoute('/_authenticated/settings/api-keys')({
  component: ApiKeysPage,
})

function ApiKeysPage() {
  return (
    <SettingsTabsLayout>
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">API Keys</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">API key management coming soon.</p>
        </CardContent>
      </Card>
    </SettingsTabsLayout>
  )
}
