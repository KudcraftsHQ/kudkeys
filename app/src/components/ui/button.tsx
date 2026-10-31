import * as React from 'react'
import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap font-sans-ui text-[13px] font-medium transition-colors disabled:pointer-events-none disabled:opacity-45 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue/35 focus-visible:ring-offset-1 [&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        // Mercury primary: near-black
        default: 'bg-ink text-paper hover:opacity-90 active:opacity-100',
        // Secondary: white on a hairline
        outline: 'bg-paper text-ink border border-line-strong shadow-[0_1px_2px_rgba(0,0,0,0.03)] hover:bg-cream',
        ghost: 'bg-transparent text-ink-soft hover:bg-cream-deep hover:text-ink',
        rose: 'bg-rose text-white hover:opacity-90',
        link: 'underline-offset-4 hover:underline text-blue p-0 h-auto',
      },
      size: {
        default: 'h-8 px-3.5 rounded-[8px]',
        sm: 'h-7 px-2.5 rounded-[7px] text-[12.5px]',
        lg: 'h-10 px-4 rounded-[9px] text-[14px]',
        icon: 'h-8 w-8 rounded-[8px]',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  },
)

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : 'button'
    return <Comp ref={ref} className={cn(buttonVariants({ variant, size, className }))} {...props} />
  },
)
Button.displayName = 'Button'
