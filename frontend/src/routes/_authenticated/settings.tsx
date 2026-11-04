import { Outlet, createFileRoute, redirect } from '@tanstack/react-router'

import { canUser } from '#/lib/permissions'

export const Route = createFileRoute('/_authenticated/settings')({
  beforeLoad: ({ context }) => {
    if (canUser(context.user, 'settings.view') === 'deny') throw redirect({ to: '/dashboard' })
  },
  component: () => <Outlet />,
})
