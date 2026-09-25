package com.qingyu.care.controller;

import com.qingyu.care.annotation.AdminOnlyWrites;
import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.AbTestReportDTO;
import com.qingyu.care.dto.AbTestUpdateRequest;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.service.AbTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A/B 实验管理 API（应用级，需 X-App-Id 请求头；实体继承 BaseTenantEntity，
 * 查询范围由多租户过滤器自动限定）。
 *
 * @author ruan
 */
@RestController
@AdminOnlyWrites
@RequestMapping("/v1/ab-experiments")
@RequiredArgsConstructor
public class AbTestController {

    private final AbTestService abTestService;

    @GetMapping
    public ApiResponse<List<AbExperiment>> list() {
        return ApiResponse.success(abTestService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<AbExperiment> getById(@PathVariable Long id) {
        return ApiResponse.success(abTestService.getById(id));
    }

    @PostMapping
    @AuditLog(action = "ABTEST_CREATE", targetType = "AbExperiment")
    public ApiResponse<AbExperiment> create(@RequestBody AbExperiment experiment) {
        return ApiResponse.success("实验已创建", abTestService.create(experiment));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "ABTEST_UPDATE", targetType = "AbExperiment")
    public ApiResponse<AbExperiment> update(@PathVariable Long id,
                                            @RequestBody AbTestUpdateRequest request) {
        return ApiResponse.success("实验已更新", abTestService.update(id, request));
    }

    @PostMapping("/{id}/start")
    @AuditLog(action = "ABTEST_START", targetType = "AbExperiment")
    public ApiResponse<AbExperiment> start(@PathVariable Long id) {
        return ApiResponse.success("实验已启动", abTestService.start(id));
    }

    @PostMapping("/{id}/end")
    @AuditLog(action = "ABTEST_END", targetType = "AbExperiment")
    public ApiResponse<AbExperiment> end(@PathVariable Long id) {
        return ApiResponse.success("实验已结束", abTestService.end(id));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<AbTestReportDTO> report(@PathVariable Long id) {
        return ApiResponse.success(abTestService.getReport(id));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "ABTEST_DELETE", targetType = "AbExperiment")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        abTestService.delete(id);
        return ApiResponse.success("实验已删除", null);
    }
}
