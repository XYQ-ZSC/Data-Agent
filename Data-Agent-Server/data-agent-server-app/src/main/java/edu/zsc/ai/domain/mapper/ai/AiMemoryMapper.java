package edu.zsc.ai.domain.mapper.ai;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import edu.zsc.ai.domain.model.entity.ai.AiMemory;

@Mapper
public interface AiMemoryMapper extends BaseMapper<AiMemory> {
}
