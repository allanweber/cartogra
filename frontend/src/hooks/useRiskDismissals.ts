import { useCallback, useEffect, useMemo, useState } from 'react'

import { useAuthStore } from '#/stores/useAuthStore'

import type { Risk } from '#/lib/topology-types'

const KEY_PREFIX = 'cartogra:risks:dismissed'

export function dismissedStorageKey(tenantId: string | undefined, userId: string | undefined): string {
  return `${KEY_PREFIX}:${tenantId ?? 'anon'}:${userId ?? 'anon'}`
}

function readIds(key: string): Set<string> {
  try {
    const raw = localStorage.getItem(key)
    return raw ? new Set(JSON.parse(raw) as string[]) : new Set()
  } catch {
    return new Set()
  }
}

function writeIds(key: string, ids: Set<string>) {
  try {
    localStorage.setItem(key, JSON.stringify([...ids]))
  } catch {
    // per-viewer convenience only — ignore write failures
  }
}

// Dismissal is a per-browser convenience, namespaced by tenant + user so a shared browser never
// carries one account's hidden risks into another. Ids of risks that no longer exist are pruned
// once a complete (non-truncated) risk list has loaded.
export function useRiskDismissals(risks: Risk[] | undefined, complete: boolean) {
  const user = useAuthStore((s) => s.user)
  const key = dismissedStorageKey(user?.tenantId, user?.id)
  const [dismissed, setDismissed] = useState<Set<string>>(() => readIds(key))

  useEffect(() => {
    setDismissed(readIds(key))
  }, [key])

  const liveIds = useMemo(() => (risks ? new Set(risks.map((r) => r.id)) : null), [risks])

  useEffect(() => {
    if (!liveIds || !complete) return
    setDismissed((current) => {
      const pruned = new Set([...current].filter((id) => liveIds.has(id)))
      if (pruned.size === current.size) return current
      writeIds(key, pruned)
      return pruned
    })
  }, [liveIds, complete, key])

  const toggle = useCallback(
    (id: string) => {
      setDismissed((current) => {
        const next = new Set(current)
        if (next.has(id)) next.delete(id)
        else next.add(id)
        writeIds(key, next)
        return next
      })
    },
    [key],
  )

  return { dismissed, toggle }
}
