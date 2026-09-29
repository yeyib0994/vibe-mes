package com.fluxmes.api.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fluxmes.api.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {}
