import { createFileRoute } from '@tanstack/react-router'

import { SettingsTabsLayout } from '#/components/SettingsTabsLayout'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'

export const Route = createFileRoute('/_authenticated/settings/notifications')({
  component: NotificationsPage,
})

function NotificationsPage() {
  return (
    <SettingsTabsLayout>
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">Notifications</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">Notification preferences coming soon.</p>
        </CardContent>
      </Card>
    </SettingsTabsLayout>
  )
}
