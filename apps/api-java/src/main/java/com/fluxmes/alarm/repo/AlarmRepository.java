package com.fluxmes.alarm.repo;

import com.fluxmes.alarm.domain.Alarm;
import com.fluxmes.alarm.domain.AlarmStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlarmRepository extends JpaRepository<Alarm, String> {

    List<Alarm> findAllByOrderByTriggeredAtDesc();

    long countByStatus(AlarmStatus status);

    /** 指定时间窗口内的报警（趋势 / 今日统计用）。 */
    List<Alarm> findByTriggeredAtBetween(OffsetDateTime from, OffsetDateTime to);

    /** 高频报警源排行（spec FR-6）：近 N 日按设备聚合降序。 */
    @Query("""
            SELECT a.source AS source, COUNT(a) AS cnt
            FROM Alarm a
            WHERE a.triggeredAt >= :from
            GROUP BY a.source
            ORDER BY cnt DESC
            """)
    List<SourceCount> findTopSources(@Param("from") OffsetDateTime from);

    /** 投影：source + count。 */
    interface SourceCount {
        String getSource();
        long getCnt();
    }
}
