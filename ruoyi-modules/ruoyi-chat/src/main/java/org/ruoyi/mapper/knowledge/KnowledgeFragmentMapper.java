package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;
import org.ruoyi.domain.vo.knowledge.DocFragmentCountVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 知识片段Mapper接口
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Mapper
public interface KnowledgeFragmentMapper extends BaseMapperPlus<KnowledgeFragment, KnowledgeFragmentVo> {

    /**
     * 批量统计各文档的分块数（强类型接收，避免 Map key 大小写问题）
     *
     * @param docIds 文档 ID 列表
     * @return 每个 docId 对应的分块数列表
     */
    @Select("<script>" +
            "SELECT doc_id AS docId, COUNT(*) AS fragmentCount " +
            "FROM knowledge_fragment " +
            "WHERE doc_id IN " +
            "<foreach collection='docIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY doc_id" +
            "</script>")
    List<DocFragmentCountVo> selectFragmentCountByDocIds(@Param("docIds") List<String> docIds);
    @Select("<script>" +
            "SELECT id, doc_id AS docId, content, idx, knowledge_id AS knowledgeId " +
            "FROM knowledge_fragment " +
            "WHERE knowledge_id = #{knowledgeId} " +
            "AND MATCH (content) AGAINST (#{query} IN NATURAL LANGUAGE MODE) " +
            "ORDER BY MATCH (content) AGAINST (#{query} IN NATURAL LANGUAGE MODE) DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<KnowledgeFragmentVo> searchByKeyword(@Param("knowledgeId") Long knowledgeId, @Param("query") String query, @Param("limit") Integer limit);

    /**
     * 故障码的受控字面候选检索。命中片段可能在故障条目中途结束，因此只拼接同知识库、
     * 同文档的下一片段作为有界上下文；业务层还会裁剪到下一个故障码标题之前。
     * 调用方只能提供知识库、故障码和数量，不可传入 SQL。
     */
    @Select("""
        SELECT fragment.id, fragment.doc_id AS docId,
               CONCAT(fragment.content,
                      CASE WHEN next_fragment.id IS NULL THEN ''
                           ELSE CONCAT('\n', next_fragment.content) END) AS content,
               fragment.idx,
               fragment.knowledge_id AS knowledgeId, attachment.name AS sourceDocument
        FROM knowledge_fragment fragment
        LEFT JOIN knowledge_fragment next_fragment
               ON next_fragment.knowledge_id = fragment.knowledge_id
              AND next_fragment.doc_id = fragment.doc_id
              AND next_fragment.idx = fragment.idx + 1
        LEFT JOIN knowledge_attach attachment
               ON attachment.knowledge_id = fragment.knowledge_id
              AND attachment.doc_id = fragment.doc_id
        WHERE fragment.knowledge_id = #{knowledgeId}
          AND UPPER(fragment.content) LIKE CONCAT('%', UPPER(#{faultCode}), '%')
        ORDER BY fragment.id ASC
        LIMIT #{limit}
        """)
    List<KnowledgeFragmentVo> searchByLiteralFaultCode(@Param("knowledgeId") Long knowledgeId,
                                                        @Param("faultCode") String faultCode,
                                                        @Param("limit") Integer limit);
}
