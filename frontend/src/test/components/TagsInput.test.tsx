import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { TagsInput } from '#/components/TagsInput'

function typeTag(value: string) {
  const input = screen.getByLabelText('Add tag...')
  fireEvent.change(input, { target: { value } })
  return input
}

describe('TagsInput', () => {
  it('adds a valid tag on Enter', () => {
    const onChange = vi.fn()
    render(<TagsInput value={[]} onChange={onChange} />)

    const input = typeTag('go')
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(onChange).toHaveBeenCalledWith(['go'])
  })

  it('adds a tag when a comma is typed', () => {
    const onChange = vi.fn()
    render(<TagsInput value={['go']} onChange={onChange} />)

    const input = typeTag('postgres')
    fireEvent.keyDown(input, { key: ',' })

    expect(onChange).toHaveBeenCalledWith(['go', 'postgres'])
  })

  it('adds the pending tag on blur', () => {
    const onChange = vi.fn()
    render(<TagsInput value={[]} onChange={onChange} />)

    const input = typeTag('redis')
    fireEvent.blur(input)

    expect(onChange).toHaveBeenCalledWith(['redis'])
  })

  it('rejects a tag with characters outside the allowed pattern', () => {
    const onChange = vi.fn()
    render(<TagsInput value={[]} onChange={onChange} />)

    const input = typeTag('has space')
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(onChange).not.toHaveBeenCalled()
  })

  it('rejects a tag longer than 50 characters', () => {
    const onChange = vi.fn()
    render(<TagsInput value={[]} onChange={onChange} />)

    const input = typeTag('a'.repeat(51))
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(onChange).not.toHaveBeenCalled()
  })

  it('ignores a duplicate tag', () => {
    const onChange = vi.fn()
    render(<TagsInput value={['go']} onChange={onChange} />)

    const input = typeTag('go')
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(onChange).not.toHaveBeenCalled()
  })

  it('does not add past maxTags and disables the input at the limit', () => {
    const onChange = vi.fn()
    render(<TagsInput value={['a', 'b']} onChange={onChange} maxTags={2} />)

    const input = screen.getByLabelText('Add tag...')
    expect(input).toBeDisabled()

    fireEvent.change(input, { target: { value: 'c' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(onChange).not.toHaveBeenCalled()
  })

  it('removes the last tag on Backspace when the input is empty', () => {
    const onChange = vi.fn()
    render(<TagsInput value={['go', 'redis']} onChange={onChange} />)

    const input = screen.getByLabelText('Add tag...')
    fireEvent.keyDown(input, { key: 'Backspace' })

    expect(onChange).toHaveBeenCalledWith(['go'])
  })

  it('does not remove a tag on Backspace while the input has text', () => {
    const onChange = vi.fn()
    render(<TagsInput value={['go']} onChange={onChange} />)

    const input = typeTag('re')
    fireEvent.keyDown(input, { key: 'Backspace' })

    expect(onChange).not.toHaveBeenCalled()
  })

  it('removes a tag through its remove button', () => {
    const onChange = vi.fn()
    render(<TagsInput value={['go', 'redis']} onChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: 'Remove go' }))

    expect(onChange).toHaveBeenCalledWith(['redis'])
  })
})
