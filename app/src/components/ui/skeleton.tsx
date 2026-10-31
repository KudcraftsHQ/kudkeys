import { cn } from '@/lib/utils'

export function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  // aria-busy: assistive tech (and readiness checks) can tell a placeholder from content.
  return <div aria-busy="true" className={cn('skeleton', className)} {...props} />
}

export function TextSkeleton({ width = '100%', className }: { width?: string; className?: string }) {
  return <Skeleton style={{ width }} className={cn('h-3.5', className)} />
}
