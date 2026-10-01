package com.qingyu.care.util;

/**
 * 分页参数夹取。页码与每页条数来自请求参数，不设上限时一次可以拉几万行，负数还会让原生 SQL 报错
 * （2026-09-30 安全审查）。新增分页端点直接用这里，别在控制器里再手写一遍 {@code Math.max/min}。
 *
 * @author ruan
 */
public final class PageParams {

    /** 每页条数上限 */
    public static final int MAX_SIZE = 100;

    private PageParams() {
    }

    public static int page(int page) {
        return Math.max(0, page);
    }

    public static int size(int size) {
        return Math.max(1, Math.min(size, MAX_SIZE));
    }
}
