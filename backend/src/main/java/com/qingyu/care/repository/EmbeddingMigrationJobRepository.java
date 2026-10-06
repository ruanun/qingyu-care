package com.qingyu.care.repository;

import com.qingyu.care.entity.EmbeddingMigrationJob;
import com.qingyu.care.enums.EmbeddingMigrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmbeddingMigrationJobRepository extends JpaRepository<EmbeddingMigrationJob, Long> {

    /** 应用最近一次迁移任务（界面展示进度用） */
    Optional<EmbeddingMigrationJob> findFirstByAppIdOrderByIdDesc(Long appId);

    List<EmbeddingMigrationJob> findByStatusIn(Collection<EmbeddingMigrationStatus> statuses);

    /** 进度计数：只改计数列，不整行 save，避免覆盖并发写入的取消请求 */
    @Modifying
    @Query("UPDATE EmbeddingMigrationJob j SET j.total = :total, j.done = :done, j.failed = :failed WHERE j.id = :id")
    int updateProgress(@Param("id") Long id, @Param("total") int total, @Param("done") int done, @Param("failed") int failed);

    /** 状态 CAS：只有当前状态在 expected 里时才改，返回是否改成功 */
    @Modifying
    @Query("UPDATE EmbeddingMigrationJob j SET j.status = :to, j.message = :message, j.finishedAt = :finishedAt "
            + "WHERE j.id = :id AND j.status IN :expected")
    int transition(@Param("id") Long id, @Param("expected") Collection<EmbeddingMigrationStatus> expected,
                   @Param("to") EmbeddingMigrationStatus to, @Param("message") String message,
                   @Param("finishedAt") java.time.LocalDateTime finishedAt);
}
