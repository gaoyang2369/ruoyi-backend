package org.ruoyi.fault.evidence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.fault.evidence.entity.DiagnosisCaseEntity;

/** 诊断案例 Mapper。 */
@Mapper
public interface DiagnosisCaseMapper extends BaseMapperPlus<DiagnosisCaseEntity, DiagnosisCaseEntity> {

    /** 锁定指定案例；租户插件仍会向该 SQL 注入租户条件。 */
    @Select("SELECT * FROM fd_diagnosis_case WHERE id = #{id} FOR UPDATE")
    DiagnosisCaseEntity selectByIdForUpdate(@Param("id") Long id);
}
