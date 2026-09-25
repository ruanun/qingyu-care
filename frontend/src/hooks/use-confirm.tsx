import { useCallback, useRef, useState } from "react"

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { buttonVariants } from "@/components/ui/button-variants"

export interface ConfirmOptions {
  title: string
  description?: string
  confirmText?: string
  /** 删除、重置密钥这类不可逆操作传 true，确认按钮变红 */
  destructive?: boolean
}

/**
 * 替代 `window.confirm()` 的 Promise 风格确认框。
 *
 * ```tsx
 * const { confirm, confirmDialog } = useConfirm()
 * if (!(await confirm({ title: '确定删除吗？', destructive: true }))) return
 * ...
 * return <>{...}{confirmDialog}</>
 * ```
 *
 * 刻意做成「每个组件自带一份对话框」而不是全局 Provider：页面测试直接 render 页面即可，
 * 不必记得套一层 Provider——漏套的后果是 confirm 永远不 resolve，删除按钮点了没反应，
 * 测试只会超时而看不出原因。
 */
export function useConfirm() {
  const [options, setOptions] = useState<ConfirmOptions | null>(null)
  const [open, setOpen] = useState(false)
  const resolveRef = useRef<((ok: boolean) => void) | null>(null)

  const confirm = useCallback((opts: ConfirmOptions) => {
    // 上一个还没答复就又弹一个：旧的按取消处理，避免调用方的 await 永远挂起
    resolveRef.current?.(false)
    setOptions(opts)
    setOpen(true)
    return new Promise<boolean>((resolve) => {
      resolveRef.current = resolve
    })
  }, [])

  // 点确认时 Radix 会先触发 Action 的 onClick、再触发 onOpenChange(false)，两次都会走到这里。
  // 第二次的 resolve(false) 对已 resolve 的 Promise 是空操作，所以无害；置空只是释放引用。
  const settle = (ok: boolean) => {
    resolveRef.current?.(ok)
    resolveRef.current = null
    setOpen(false)
  }

  // options 在关闭后保留，让淡出动画期间文案不闪空
  const confirmDialog = (
    <AlertDialog open={open} onOpenChange={(o) => { if (!o) settle(false) }}>
      {/* 无描述时显式声明，否则 Radix 会在控制台警告缺少 Description */}
      <AlertDialogContent {...(options?.description ? {} : { "aria-describedby": undefined })}>
        <AlertDialogHeader>
          <AlertDialogTitle>{options?.title}</AlertDialogTitle>
          {options?.description ? (
            <AlertDialogDescription>{options.description}</AlertDialogDescription>
          ) : null}
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>取消</AlertDialogCancel>
          <AlertDialogAction
            className={options?.destructive ? buttonVariants({ variant: "destructive" }) : undefined}
            onClick={() => settle(true)}
          >
            {options?.confirmText ?? "确定"}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )

  return { confirm, confirmDialog }
}
