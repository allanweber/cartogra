import { describe, expect, it, vi } from 'vitest'

import { preventNavigationFromPopoverTrigger } from '#/lib/utils'

function clickEvent(target: HTMLElement): React.MouseEvent {
  return { target, preventDefault: vi.fn() } as unknown as React.MouseEvent
}

describe('preventNavigationFromPopoverTrigger', () => {
  it('prevents default when the click target is a popover trigger', () => {
    const trigger = document.createElement('button')
    trigger.setAttribute('data-slot', 'popover-trigger')
    const event = clickEvent(trigger)

    preventNavigationFromPopoverTrigger(event)

    expect(event.preventDefault).toHaveBeenCalled()
  })

  it('prevents default when the click target is nested inside a popover trigger', () => {
    const trigger = document.createElement('button')
    trigger.setAttribute('data-slot', 'popover-trigger')
    const icon = document.createElement('svg')
    trigger.appendChild(icon)
    const event = clickEvent(icon)

    preventNavigationFromPopoverTrigger(event)

    expect(event.preventDefault).toHaveBeenCalled()
  })

  it('does nothing when the click target is elsewhere in the card', () => {
    const heading = document.createElement('span')
    const event = clickEvent(heading)

    preventNavigationFromPopoverTrigger(event)

    expect(event.preventDefault).not.toHaveBeenCalled()
  })
})
