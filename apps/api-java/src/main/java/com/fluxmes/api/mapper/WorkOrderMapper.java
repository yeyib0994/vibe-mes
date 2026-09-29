package com.fluxmes.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fluxmes.api.entity.WorkOrder;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {}
