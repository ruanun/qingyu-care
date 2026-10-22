package com.qingyu.care.repository.projection;

/**
 * 文档 id+name 行（零命中清单与排行名称回填共用）。
 *
 * @author ruan
 */
public interface DocIdNameRow {
    Long getId();
    String getName();
}
