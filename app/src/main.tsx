import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { Toaster } from 'sonner'

import './index.css'
import { App } from './App'
import { queryClient } from './lib/api'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
      <Toaster
        position="bottom-center"
        toastOptions={{
          style: {
            fontFamily: 'var(--font-sans)',
            background: 'var(--ink)',
            color: 'var(--cream)',
            border: '1px solid var(--line-strong)',
            borderRadius: '8px',
            fontSize: '13px',
          },
        }}
      />
    </QueryClientProvider>
  </StrictMode>,
)
