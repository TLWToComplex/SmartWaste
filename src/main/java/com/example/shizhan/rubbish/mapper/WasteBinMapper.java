package com.example.shizhan.rubbish.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.shizhan.rubbish.entity.WasteBin;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WasteBinMapper extends BaseMapper<WasteBin> {
    @Select("SELECT * FROM waste_bin WHERE id = #{id} FOR UPDATE")
    WasteBin lockById(@Param("id") Long id);
}
