import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { request } from './http'
import { clearSession, Session, setSession } from '../lib/auth'

/** POST /api/auth/login —— { token, userId, username, displayName, role } */
export function useLogin(onSuccess: (s: Session) => void) {
  const [error, setError] = useState<string | null>(null)

  const login = useMutation({
    mutationFn: (body: { username: string; password: string }) =>
      request('/api/auth/login', { method: 'POST', body }),
    onSuccess: (data) => {
      const s: Session = {
        token: data.token,
        userId: data.userId,
        username: data.username,
        displayName: data.displayName ?? data.username,
        role: data.role,
      }
      setSession(s)
      setError(null)
      onSuccess(s)
    },
    onError: (e: Error) => setError(e.message || '登录失败'),
  })

  return { login: login.mutate, pending: login.isPending, error, resetError: () => setError(null) }
}

export function logout() {
  clearSession()
  location.reload()
}
