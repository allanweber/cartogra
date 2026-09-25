import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

// A popover trigger nested inside a whole-card/row Link (e.g. a risk score badge)
// manages its own click-to-open state; letting the click also navigate the Link would
// fire both at once. Every Radix popover trigger carries data-slot="popover-trigger"
// (see #/components/ui/popover.tsx), so this guards any such trigger generically — it
// isn't wired per-widget. preventDefault (not stopPropagation) is required: Radix's own
// trigger-level click handling already ran by the time this fires during bubbling, so
// it's unaffected, but TanStack Router's Link composes a caller onClick before its own
// navigate handler and skips navigating once defaultPrevented is set.
export function preventNavigationFromPopoverTrigger(event: React.MouseEvent) {
  if ((event.target as HTMLElement).closest('[data-slot="popover-trigger"]')) {
    event.preventDefault()
  }
}
