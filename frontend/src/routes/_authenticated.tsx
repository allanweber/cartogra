import { Outlet, createFileRoute, redirect } from '@tanstack/react-router'
import { useEffect, useRef } from 'react'

import { OnboardingWizard } from '#/components/OnboardingWizard'
import { tryRefresh } from '#/lib/api'
import { fetchSession } from '#/lib/session'
import { useAuthStore } from '#/stores/useAuthStore'

const BUFFER_MS = 2 * 60 * 1000 // refresh 2 minutes before expiry
const FALLBACK_REFRESH_MS = 13 * 60 * 1000 // used when tokenExpiresAt is unknown

export const Route = createFileRoute('/_authenticated')({
  beforeLoad: async ({ context, location }) => {
    if (typeof window === 'undefined') {
      const session = await fetchSession()
      if (!session) throw redirect({ to: '/login', search: { redirect: location.pathname } })
      context.queryClient.setQueryData(['session'], session)
      return { user: session.user, tokenExpiresAt: session.tokenExpiresAt }
    }

    const { isHydrated, isAuthenticated, user: storeUser, tokenExpiresAt } = useAuthStore.getState()
    if (isHydrated) {
      if (!isAuthenticated) throw redirect({ to: '/login', search: { redirect: location.pathname } })
      return { user: storeUser, tokenExpiresAt }
    }

    const session = await context.queryClient.ensureQueryData({
      queryKey: ['session'],
      queryFn: fetchSession,
      staleTime: 5 * 60 * 1000,
    })
    if (!session) throw redirect({ to: '/login', search: { redirect: location.pathname } })
    useAuthStore.getState().hydrateWith(session.user, session.tokenExpiresAt ?? undefined)
    return { user: session.user, tokenExpiresAt: session.tokenExpiresAt }
  },
  component: AuthenticatedLayout,
})

function useProactiveRefresh() {
  const tokenExpiresAt = useAuthStore((s) => s.tokenExpiresAt)
  const setTokenExpiresAt = useAuthStore((s) => s.setTokenExpiresAt)
  const clearAuth = useAuthStore((s) => s.clearAuth)

  // Use a ref so the async callback can cancel its own retry without stale closure issues.
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    const delay =
      tokenExpiresAt != null
        ? Math.max(0, tokenExpiresAt * 1000 - Date.now() - BUFFER_MS)
        : FALLBACK_REFRESH_MS

    async function doRefresh() {
      const baseUrl = import.meta.env.VITE_API_BASE_URL ?? ''
      const result = await tryRefresh(baseUrl)
      if (!result) {
        clearAuth()
        window.location.href = '/login'
        return
      }
      // Update expiry — this triggers the effect to reschedule the next refresh.
      setTokenExpiresAt(Math.floor(Date.now() / 1000) + result.expiresIn)
    }

    timerRef.current = setTimeout(doRefresh, delay)
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [tokenExpiresAt, setTokenExpiresAt, clearAuth])
}

function AuthenticatedLayout() {
  const { user, tokenExpiresAt } = Route.useRouteContext()
  const { isHydrated, hydrateWith } = useAuthStore()
  const isAdmin = useAuthStore((s) => s.user?.roles.includes('ADMIN') ?? false)
  useProactiveRefresh()

  useEffect(() => {
    if (isHydrated || !user) return
    // SSR path: store not yet hydrated — hydrate from route context.
    hydrateWith(user, tokenExpiresAt ?? undefined)
  }, [])

  return (
    <>
      <Outlet />
      {isAdmin && <OnboardingWizard />}
    </>
  )
}
