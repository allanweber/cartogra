import { useEffect, useState } from 'react'

const QUERY = '(prefers-reduced-motion: reduce)'

// The global CSS `prefers-reduced-motion` rule in styles.css only zeroes
// CSS transition/animation durations — it can't reach a JS-driven simulation
// like D3's force layout, so consumers that animate via `requestAnimationFrame`
// or a tick loop need this to gate that behavior themselves.
export function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() =>
    typeof window === 'undefined' ? false : window.matchMedia(QUERY).matches,
  )

  useEffect(() => {
    const mql = window.matchMedia(QUERY)
    const onChange = () => setReduced(mql.matches)
    onChange()
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }, [])

  return reduced
}
