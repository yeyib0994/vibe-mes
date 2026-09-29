package com.fluxmes.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fluxmes.api.entity.EquipmentMetric;
import org.apache.ibatis.annotations.Mapper;

/**
 * J1 · 设备参数时序表 Mapper。
 *
 * <p>本项目**没有 @MapperScan**，Mapper 必须显式标注 {@code @Mapper}，
 * 否则编译通过但启动期报 {@code No qualifying bean of type ...Mapper}。
 */
@Mapper
public interface EquipmentMetricMapper extends BaseMapper<EquipmentMetric> {}
