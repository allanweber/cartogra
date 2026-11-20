import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ContractCompatibility } from '#/components/ContractCompatibility'

import type { CompatibilityChange } from '#/lib/contract-types'

const ADDED: CompatibilityChange = {
  rule: 'path-added',
  breaking: false,
  direction: 'operation',
  method: null,
  path: '/history',
  location: null,
  message: 'Path /history was added',
}
const REMOVED: CompatibilityChange = {
  rule: 'parameter-required-added',
  breaking: true,
  direction: 'request',
  method: 'GET',
  path: '/entries',
  location: 'query.currency',
  message: 'Required parameter query.currency was added',
}

describe('ContractCompatibility', () => {
  it('groups the breaking changes first', () => {
    render(<ContractCompatibility compatibility={{ isBreaking: true, changes: [ADDED, REMOVED] }} />)

    const lists = screen.getAllByRole('list')
    expect(lists[0]).toHaveAccessibleName('Breaking changes')
    expect(lists[1]).toHaveAccessibleName('Compatible changes')
    expect(within(lists[0]).getByText('parameter-required-added')).toBeInTheDocument()
    expect(within(lists[0]).getByText(/GET \/entries/)).toBeInTheDocument()
    expect(within(lists[0]).getByText('· query.currency')).toBeInTheDocument()
    expect(within(lists[0]).getByText('Required parameter query.currency was added')).toBeInTheDocument()
    expect(within(lists[1]).getByText('path-added')).toBeInTheDocument()
    expect(screen.getByText('Breaking')).toBeInTheDocument()
  })

  it('says why a version with no rule hits is compatible', () => {
    render(<ContractCompatibility compatibility={{ isBreaking: false, changes: [] }} />)

    expect(screen.getByText('Compatible')).toBeInTheDocument()
    expect(screen.getByText('No breaking changes found by the compatibility rules.')).toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })
})
