import { QueryClient } from '@tanstack/react-query'

// 全局 QueryClient：默认 30s 刷新周期与降级策略，与 constitution P4「实时数据须标注时效」对齐。
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: 1,
      staleTime: 30_000,
      refetchIntervalInBackground: false,
    },
  },
})
