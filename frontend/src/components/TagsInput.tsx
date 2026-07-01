import { useState, type KeyboardEvent } from 'react'
import { X } from 'lucide-react'
import { Badge } from '#/components/ui/badge'
import { Input } from '#/components/ui/input'

interface TagsInputProps {
  value: string[]
  onChange: (tags: string[]) => void
  maxTags?: number
  placeholder?: string
}

const TAG_PATTERN = /^[a-zA-Z0-9._:-]+$/

export function TagsInput({ value, onChange, maxTags = 20, placeholder = 'Add tag...' }: TagsInputProps) {
  const [inputValue, setInputValue] = useState('')

  function addTag(raw: string) {
    const tag = raw.trim()
    if (!tag || !TAG_PATTERN.test(tag) || tag.length > 50) return
    if (value.includes(tag) || value.length >= maxTags) return
    onChange([...value, tag])
    setInputValue('')
  }

  function removeTag(tag: string) {
    onChange(value.filter((t) => t !== tag))
  }

  function handleKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      addTag(inputValue)
    } else if (e.key === 'Backspace' && !inputValue && value.length > 0) {
      onChange(value.slice(0, -1))
    }
  }

  return (
    <div className="flex min-h-9 flex-wrap items-center gap-1.5 rounded-md border border-input bg-transparent px-3 py-1.5 text-sm focus-within:ring-1 focus-within:ring-ring">
      {value.map((tag) => (
        <Badge key={tag} variant="secondary" className="gap-1 pr-1">
          {tag}
          <button
            type="button"
            onClick={() => removeTag(tag)}
            className="ml-0.5 rounded-full hover:bg-muted-foreground/20"
            aria-label={`Remove ${tag}`}
          >
            <X className="size-3" />
          </button>
        </Badge>
      ))}
      <Input
        value={inputValue}
        onChange={(e) => setInputValue(e.target.value)}
        onKeyDown={handleKeyDown}
        onBlur={() => addTag(inputValue)}
        placeholder={value.length === 0 ? placeholder : undefined}
        className="h-auto min-w-30 flex-1 border-0 p-0 shadow-none focus-visible:ring-0"
        disabled={value.length >= maxTags}
      />
    </div>
  )
}
