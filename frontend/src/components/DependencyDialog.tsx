import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Search, X } from 'lucide-react'
import { useEffect, useState } from 'react'

import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '#/components/ui/dialog'
import { Input } from '#/components/ui/input'
import { Label } from '#/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '#/components/ui/select'
import { Textarea } from '#/components/ui/textarea'
import { useDebounce } from '#/hooks/useDebounce'
import { ApiError, apiFetch } from '#/lib/api'

import type { PageResult, RegistryService } from '#/lib/registry-types'
import type { DependencyDirectionEntry, DependencyProtocol, DependencyResponse } from '#/lib/topology-types'

const PROTOCOLS: DependencyProtocol[] = ['HTTP', 'GRPC', 'KAFKA', 'DB']

interface DependencyDialogProps {
  serviceId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  edit?: { entry: DependencyDirectionEntry; direction: 'upstream' | 'downstream' }
}

export function DependencyDialog({ serviceId, open, onOpenChange, edit }: DependencyDialogProps) {
  const queryClient = useQueryClient()
  const isEdit = !!edit

  const [selectedTarget, setSelectedTarget] = useState<RegistryService | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [protocol, setProtocol] = useState<DependencyProtocol>(edit?.entry.protocol ?? 'HTTP')
  const [metadata, setMetadata] = useState(edit?.entry.metadata ?? '')

  const debouncedQuery = useDebounce(searchQuery, 250)

  const { data: searchResults, isFetching: isSearching } = useQuery({
    queryKey: ['services', 'search', debouncedQuery],
    queryFn: () =>
      apiFetch<PageResult<RegistryService>>(
        `/v1/registry/services?search=${encodeURIComponent(debouncedQuery)}&limit=20`,
      ),
    enabled: !isEdit && debouncedQuery.trim().length > 0 && !selectedTarget,
  })

  const mutation = useMutation({
    mutationFn: () => {
      if (edit) {
        const sourceServiceId = edit.direction === 'downstream' ? serviceId : edit.entry.serviceId
        const targetServiceId = edit.direction === 'downstream' ? edit.entry.serviceId : serviceId
        return apiFetch<DependencyResponse>(`/v1/topology/dependencies/${edit.entry.id}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ sourceServiceId, targetServiceId, protocol, metadata: metadata || null }),
        })
      }
      return apiFetch<DependencyResponse>('/v1/topology/dependencies', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sourceServiceId: serviceId,
          targetServiceId: selectedTarget!.id,
          protocol,
          metadata: metadata || null,
        }),
      })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['dependencies', serviceId] })
      onOpenChange(false)
    },
  })

  useEffect(() => {
    if (open) return
    mutation.reset()
    setSelectedTarget(null)
    setSearchQuery('')
    setProtocol(edit?.entry.protocol ?? 'HTTP')
    setMetadata(edit?.entry.metadata ?? '')
  }, [open])

  function handleClose() {
    if (mutation.isPending) return
    onOpenChange(false)
  }

  const canSubmit = isEdit || !!selectedTarget
  const candidates = (searchResults?.items ?? []).filter((service) => service.id !== serviceId)

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent
        className="gap-0 p-0 sm:max-w-120"
        showCloseButton={false}
        onInteractOutside={(event) => event.preventDefault()}
      >
        <div className="border-b px-6 py-5">
          <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">
            {isEdit ? 'Edit Dependency' : 'Add Dependency'}
          </p>
          <DialogDescription className="sr-only">
            {isEdit ? 'Edit this dependency.' : 'Declare a new dependency for this service.'}
          </DialogDescription>
          <div className="mt-1 flex items-start justify-between gap-4">
            <DialogTitle className="text-xl font-bold">{isEdit ? edit.entry.name : 'New dependency'}</DialogTitle>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={handleClose}
              className="mt-1 text-muted-foreground opacity-70 hover:opacity-100"
            >
              <X className="size-4" />
              <span className="sr-only">Close</span>
            </Button>
          </div>
        </div>

        <form
          className="flex flex-col"
          onSubmit={(event) => {
            event.preventDefault()
            mutation.mutate()
          }}
        >
          <div className="space-y-5 px-6 py-6">
            {mutation.error && (
              <Alert variant="destructive">
                <AlertDescription>
                  {mutation.error.message}
                  {mutation.error instanceof ApiError && ` (trace: ${mutation.error.traceId})`}
                </AlertDescription>
              </Alert>
            )}

            <div className="space-y-1.5">
              <Label>Target service</Label>
              {isEdit ? (
                <p className="rounded-md border border-border bg-muted px-3 py-2 text-sm text-muted-foreground">
                  {edit.entry.name}
                </p>
              ) : selectedTarget ? (
                <div className="flex items-center justify-between rounded-md border border-border px-3 py-2 text-sm">
                  <span className="font-medium">{selectedTarget.name}</span>
                  <Button type="button" variant="ghost" size="sm" onClick={() => setSelectedTarget(null)}>
                    Change
                  </Button>
                </div>
              ) : (
                <div className="relative">
                  <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    autoFocus
                    value={searchQuery}
                    onChange={(event) => setSearchQuery(event.target.value)}
                    placeholder="Search services…"
                    className="pl-9"
                  />
                  {debouncedQuery.trim().length > 0 && (
                    <div className="absolute z-10 mt-1 max-h-56 w-full overflow-y-auto rounded-md border border-border bg-popover shadow-md">
                      {isSearching ? (
                        <p className="px-3 py-2 text-sm text-muted-foreground">Searching…</p>
                      ) : candidates.length === 0 ? (
                        <p className="px-3 py-2 text-sm text-muted-foreground">No services found.</p>
                      ) : (
                        candidates.map((service) => (
                          <Button
                            key={service.id}
                            type="button"
                            variant="ghost"
                            onClick={() => {
                              setSelectedTarget(service)
                              setSearchQuery('')
                            }}
                            className="h-auto w-full justify-start rounded-none px-3 py-2 text-left text-sm font-normal"
                          >
                            {service.name}
                          </Button>
                        ))
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="dependency-protocol">Protocol</Label>
              <Select value={protocol} onValueChange={(value) => setProtocol(value as DependencyProtocol)}>
                <SelectTrigger id="dependency-protocol">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PROTOCOLS.map((value) => (
                    <SelectItem key={value} value={value}>
                      {value}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="dependency-metadata">
                Metadata <span className="text-sm font-normal text-muted-foreground">optional</span>
              </Label>
              <Textarea
                id="dependency-metadata"
                value={metadata}
                onChange={(event) => setMetadata(event.target.value)}
                rows={2}
                className="resize-none"
                placeholder="e.g. endpoint path"
              />
            </div>
          </div>

          <div className="flex items-center justify-end gap-2 border-t px-6 py-4">
            <Button type="button" variant="outline" onClick={handleClose} disabled={mutation.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={!canSubmit || mutation.isPending}>
              {mutation.isPending ? 'Saving…' : isEdit ? 'Save changes' : 'Add dependency'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}
