import { QueryClient } from '@tanstack/react-query'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5 * 60 * 1000,
      gcTime: 10 * 60 * 1000,
      retry: 1,
    },
    mutations: {
      retry: 0,
    },
  },
})

export function getContext() {
  return { queryClient }
}
export default function TanstackQueryProvider() {}
