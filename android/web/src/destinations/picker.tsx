/*
 * ren's model picker on the phone reads Model Provider, Model, Reasoning Effort, Research Depth (owner, 2026-11-20).
 * ren's ModelSelector draws the first three; its popover is this file (vite.config.ts hands it to ModelSelector alone),
 * which is ren's own popover with whatever `PickerFooter` holds under them. The shell (shell.tsx) puts `ResearchDepth`
 * (research-depth.tsx) there for every picker on ren's pages, and page.css hides the depth control the Research hero
 * draws beside its picker. This file imports nothing of ren's but the popover, so ModelSelector's imports stay free of cycles.
 */
import { createContext, forwardRef, useContext, type ComponentPropsWithoutRef, type ElementRef, type ReactNode } from "react"
import { Popover, PopoverAnchor, PopoverContent as RenPopoverContent, PopoverTrigger } from "../ren/components/ui/popover"

/** What the model picker shows under its reasoning levels; nothing unless a page provides it. */
export const PickerFooter = createContext<ReactNode>(null)

const PopoverContent = forwardRef<ElementRef<typeof RenPopoverContent>, ComponentPropsWithoutRef<typeof RenPopoverContent>>(
  ({ children, ...props }, ref) => {
    const footer = useContext(PickerFooter)
    return (
      <RenPopoverContent ref={ref} {...props}>
        {children}
        {footer}
      </RenPopoverContent>
    )
  },
)
PopoverContent.displayName = "PopoverContent"

export { Popover, PopoverAnchor, PopoverContent, PopoverTrigger }
