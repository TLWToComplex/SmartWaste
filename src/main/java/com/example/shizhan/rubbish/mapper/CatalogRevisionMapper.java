package com.example.shizhan.rubbish.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface CatalogRevisionMapper {
    @Select("SELECT revision FROM catalog_revision WHERE id = 1") long revision();
    @Update("UPDATE catalog_revision SET revision = revision + 1 WHERE id = 1") int advance();
}
