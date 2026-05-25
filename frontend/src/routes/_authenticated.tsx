import { Outlet, createFileRoute, redirect } from '@tanstack/react-router'
import { useEffect } from 'react'

import { fetchSession } from '#/lib/session'
import { useAuthStore } from '#/stores/useAuthStore'

export const Route = createFileRoute('/_authenticated')({
  beforeLoad: async ({ context, location }) => {
    if (typeof window === 'undefined') {
      const user = await fetchSession()
      if (!user) throw redirect({ to: '/login', search: { redirect: location.pathname } })
      context.queryClient.setQueryData(['session'], user)
      return { user }
    }

    const { isHydrated, isAuthenticated, user: storeUser } = useAuthStore.getState()
    if (isHydrated) {
      if (!isAuthenticated) throw redirect({ to: '/login', search: { redirect: location.pathname } })
      return { user: storeUser }
    }

    const user = await context.queryClient.ensureQueryData({
      queryKey: ['session'],
      queryFn: fetchSession,
      staleTime: 5 * 60 * 1000,
    })
    if (!user) throw redirect({ to: '/login', search: { redirect: location.pathname } })
    useAuthStore.getState().hydrateWith(user)
    return { user }
  },
  component: AuthenticatedLayout,
})

function AuthenticatedLayout() {
  const { user } = Route.useRouteContext()
  const { isHydrated, hydrateWith } = useAuthStore()

  useEffect(() => {
    if (isHydrated || !user) return
    hydrateWith(user)
  }, [])

  return <Outlet />
}
