import * as React from 'react'
import { cn } from '@/lib/utils'

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input
      ref={ref}
      className={cn(
        'flex h-9 w-full rounded-[8px] border border-line-strong bg-paper px-3 text-[14px] font-sans-ui text-ink shadow-[0_1px_2px_rgba(0,0,0,0.03)]',
        'placeholder:text-muted/70 placeholder:font-normal',
        'focus-visible:outline-none focus-visible:border-blue focus-visible:ring-2 focus-visible:ring-blue/20',
        'disabled:cursor-not-allowed disabled:opacity-50',
        'transition-[border-color,box-shadow] duration-150',
        className,
      )}
      {...props}
    />
  ),
)
Input.displayName = 'Input'
