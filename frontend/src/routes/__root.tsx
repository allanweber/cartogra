import {
  HeadContent,
  Link,
  Navigate,
  Outlet,
  Scripts,
  createRootRouteWithContext,
} from '@tanstack/react-router'
import { TanStackRouterDevtoolsPanel } from '@tanstack/react-router-devtools'
import { TanStackDevtools } from '@tanstack/react-devtools'

import TanStackQueryDevtools from '../integrations/tanstack-query/devtools'
import { AppLayout } from '../components/AppLayout'
import { Alert, AlertDescription, AlertTitle } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { Toaster } from '../components/ui/sonner'
import { TooltipProvider } from '../components/ui/tooltip'
import { ApiError } from '../lib/api'
import { useAuthStore } from '../stores/useAuthStore'

import appCss from '../styles.css?url'

import type { QueryClient } from '@tanstack/react-query'

interface MyRouterContext {
  queryClient: QueryClient
}

export const Route = createRootRouteWithContext<MyRouterContext>()({
  head: () => ({
    meta: [
      {
        charSet: 'utf-8',
      },
      {
        name: 'viewport',
        content: 'width=device-width, initial-scale=1',
      },
      {
        title: 'Cartogra',
      },
    ],
    links: [
      {
        rel: 'stylesheet',
        href: appCss,
      },
    ],
  }),
  component: RootLayout,
  notFoundComponent: RootNotFound,
  errorComponent: RootErrorBoundary,
  shellComponent: RootDocument,
})

function RootLayout() {
  return <Outlet />
}

function RootDocument({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <HeadContent />
      </head>
      <body>
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:rounded-md focus:bg-background focus:px-4 focus:py-2 focus:text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
        >
          Skip to main content
        </a>
        <TooltipProvider>
          {children}
          <Toaster />
          <TanStackDevtools
            config={{
              position: 'bottom-right',
            }}
            plugins={[
              {
                name: 'Tanstack Router',
                render: <TanStackRouterDevtoolsPanel />,
              },
              TanStackQueryDevtools,
            ]}
          />
        </TooltipProvider>
        <Scripts />
      </body>
    </html>
  )
}

function RootNotFound() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)

  if (!isAuthenticated) {
    return <Navigate to="/login" />
  }

  return (
    <AppLayout
      title="This page does not exist"
      description="The destination may have moved, or the URL may be incomplete."
      eyebrow="Not found"
      actions={
        <Button asChild>
          <Link to="/dashboard">Go to Catalog</Link>
        </Button>
      }
    >
      <Alert>
        <AlertTitle>Use navigation to choose another workspace view.</AlertTitle>
        <AlertDescription>
          The shell is still available, so you can safely jump back to a known route.
        </AlertDescription>
      </Alert>
    </AppLayout>
  )
}

export function RootErrorBoundary({
  error,
  reset,
}: {
  error: Error
  reset: () => void
}) {
  const traceId = error instanceof ApiError ? error.traceId : null

  return (
    <AppLayout
      title="Something interrupted this view"
      description="Cartogra could not render this screen. Retry the view, or return to a safe starting point."
      eyebrow="Recovery"
      actions={
        <div className="flex flex-wrap items-center gap-3">
          <Button onClick={() => reset()}>Retry</Button>
          <Button asChild variant="outline">
            <a href="/dashboard">Go to Catalog</a>
          </Button>
        </div>
      }
    >
      <Alert variant="destructive">
        <AlertTitle>{error.message || 'Unknown rendering error'}</AlertTitle>
        <AlertDescription>
          {traceId ? `Trace ID: ${traceId}` : 'No trace ID was attached to this error.'}
        </AlertDescription>
      </Alert>
    </AppLayout>
  )
}
