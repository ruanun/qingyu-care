package com.qingyu.care.enums;

/**
 * 切换向量模型的迁移任务状态。
 *
 * @author ruan
 */
public enum EmbeddingMigrationStatus {
    /** 正在给目标模型补向量；检索仍读当前模型 */
    RUNNING,
    /** 已请求取消，任务在下一个检查点停下并清理 */
    CANCELLING,
    /** 已切换到目标模型、旧向量已清理 */
    COMPLETED,
    /** 已取消：目标模型的向量已清掉，应用仍用原模型 */
    CANCELLED,
    /** 失败（有条目始终写不进目标模型等）：切换仍挂起，可重试或取消 */
    FAILED,
    /** 进程重启打断了任务：切换仍挂起，可继续或取消 */
    INTERRUPTED;

    /** 终态之外、仍占着「切换进行中」的状态 */
    public boolean isActive() {
        return this == RUNNING || this == CANCELLING;
    }
}
