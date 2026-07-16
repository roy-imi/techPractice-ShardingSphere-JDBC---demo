package com.example.assetinspection.migration.source;

import com.example.assetinspection.domain.InspectionRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * 旧单表的最小只读端口。
 *
 * <p>Service 只依赖这个接口，因此单元测试可以用内存列表/Mockito 替代 MySQL，
 * 也让“旧库读取”和“新库分片写入”的职责边界非常明确。</p>
 */
public interface LegacyInspectionRecordSource extends AutoCloseable {

    /**
     * 按主键游标向后读取，SQL 必须是 id &gt; afterId，而不是 OFFSET。
     *
     * @param afterId 上一批最后成功提交的旧表主键；首次迁移传 0
     * @param limit 本次最多读取条数；Service 会多读一条判断是否还有下一批
     * @return 按 id 严格升序的历史记录
     */
    List<InspectionRecord> readAfterId(long afterId, int limit);

    /** 按租户与半开日期区间统计旧单表数量，用于迁移后对账。 */
    long count(Long tenantId, LocalDate startDate, LocalDate endDateExclusive);

    /** 关闭内部旧库连接池。 */
    @Override
    void close();
}
