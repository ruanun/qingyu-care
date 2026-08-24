// widget/src/core/attachments.ts
// 附件的纯数据形状（DTO），供 core/api.ts 的 OpenSessionResponse 与 core/history.ts 使用。
// DOM 渲染（图片内嵌预览/文件下载条）在 widget/src/attachments.ts——那部分依赖 document，
// 不属于 core（core 不得 import widget 层，见 core/platform.ts 顶部说明）。

/** 消息挂载的一个附件（图片/文件）的元数据。 */
export interface WidgetAttachment {
  id: number;
  fileName: string;
  contentType: string;
  fileSize: number;
  /** webp 恒为 null（JDK 内置 ImageIO 无 WebP 解码器），占位逻辑须容忍 */
  width?: number | null;
  height?: number | null;
}
