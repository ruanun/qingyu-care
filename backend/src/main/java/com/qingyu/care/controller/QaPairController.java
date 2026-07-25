package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.QaPairRequest;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.service.QaPairService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** QA 问答对管理 API */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class QaPairController {

    private final QaPairService qaPairService;

    @GetMapping("/knowledge/{kbId}/qa-pairs")
    public ApiResponse<List<QaPair>> list(@PathVariable Long kbId) {
        return ApiResponse.success(qaPairService.list(kbId));
    }

    @AuditLog(action = "QA_CREATE", targetType = "QaPair")
    @PostMapping("/knowledge/{kbId}/qa-pairs")
    public ApiResponse<QaPair> create(@PathVariable Long kbId, @RequestBody QaPairRequest req) {
        return ApiResponse.success("问答对已创建", qaPairService.create(kbId, req.getQuestion(), req.getAnswer()));
    }

    @AuditLog(action = "QA_UPDATE", targetType = "QaPair")
    @PutMapping("/qa-pairs/{id}")
    public ApiResponse<QaPair> update(@PathVariable Long id, @RequestBody QaPairRequest req) {
        return ApiResponse.success("问答对已更新", qaPairService.update(id, req.getQuestion(), req.getAnswer()));
    }

    @AuditLog(action = "QA_APPROVE", targetType = "QaPair")
    @PostMapping("/qa-pairs/{id}/approve")
    public ApiResponse<QaPair> approve(@PathVariable Long id) {
        return ApiResponse.success("问答对已通过复核", qaPairService.approve(id));
    }

    @AuditLog(action = "QA_ENABLED", targetType = "QaPair")
    @PutMapping("/qa-pairs/{id}/enabled")
    public ApiResponse<QaPair> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        return ApiResponse.success(enabled ? "问答对已启用" : "问答对已停用", qaPairService.setEnabled(id, enabled));
    }

    @AuditLog(action = "QA_DELETE", targetType = "QaPair")
    @DeleteMapping("/qa-pairs/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        qaPairService.delete(id);
        return ApiResponse.success("问答对已删除", null);
    }
}
