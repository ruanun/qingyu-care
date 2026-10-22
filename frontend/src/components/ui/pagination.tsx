import { ChevronLeft, ChevronRight } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

interface PaginationProps {
  /** 当前页，从 0 开始（与后端 Spring Data 的 page 参数一致） */
  page: number
  totalPages: number
  onPageChange: (page: number) => void
  className?: string
}

/** 上一页 / 页码 / 下一页。totalPages 为 0 时按 1 页显示，两个按钮都禁用。 */
export function Pagination({ page, totalPages, onPageChange, className }: PaginationProps) {
  const total = Math.max(totalPages, 1)
  return (
    <div className={cn("flex items-center justify-end gap-2", className)}>
      <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>
        <ChevronLeft className="w-4 h-4 mr-1" />
        上一页
      </Button>
      <span className="text-sm text-muted-foreground tabular-nums">
        {page + 1} / {total}
      </span>
      <Button variant="outline" size="sm" disabled={page >= total - 1} onClick={() => onPageChange(page + 1)}>
        下一页
        <ChevronRight className="w-4 h-4 ml-1" />
      </Button>
    </div>
  )
}
