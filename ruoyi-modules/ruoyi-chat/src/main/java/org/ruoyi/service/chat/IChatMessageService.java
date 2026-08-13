package org.ruoyi.service.chat;

import dev.langchain4j.data.message.ChatMessage;
import org.ruoyi.common.chat.domain.bo.chat.ChatMessageBo;
import org.ruoyi.common.chat.domain.vo.chat.ChatMessageVo;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;

import java.util.Collection;
import java.util.List;

/**
 * 聊天消息Service接口
 *
 * @author ageerle
 * @date 2025-12-14
 */
public interface IChatMessageService {

    /**
     * 查询聊天消息
     *
     * @param id 主键
     * @return 聊天消息
     */
    ChatMessageVo queryById(Long id);

    /**
     * 分页查询聊天消息列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 聊天消息分页列表
     */
    TableDataInfo<ChatMessageVo> queryPageList(ChatMessageBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的聊天消息列表
     *
     * @param bo 查询条件
     * @return 聊天消息列表
     */
    List<ChatMessageVo> queryList(ChatMessageBo bo);

    /**
     * 新增聊天消息
     *
     * @param bo 聊天消息
     * @return 是否新增成功
     */
    Boolean insertByBo(ChatMessageBo bo);

    /**
     * 修改聊天消息
     *
     * @param bo 聊天消息
     * @return 是否修改成功
     */
    Boolean updateByBo(ChatMessageBo bo);

    /**
     * 校验并批量删除聊天消息信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 根据会话ID获取所有消息
     * 用于长期记忆功能
     *
     * @param sessionId 会话ID
     * @return 消息DTO列表
     */
    List<ChatMessage> getMessagesBySessionId(Long sessionId);

    /**
     * 按会话和所属用户读取最近消息，返回仍按时间（ID）从旧到新排序。
     * 此方法供需要会话指代的受限规划器使用，不能以空参数扩大查询范围。
     */
    List<ChatMessage> getMessagesBySessionIdAndUserId(Long sessionId, Long userId, int maxMessages);

    /**
     * 根据会话ID删除所有消息
     * 用于清理会话历史
     *
     * @param sessionId 会话ID
     * @return 是否删除成功
     */
    Boolean deleteBySessionId(Long sessionId);

    /**
     * 保存聊天消息
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param content   消息内容
     * @param role      角色类型
     * @param modelName 模型名称
     */
    void saveChatMessage(Long userId, Long sessionId, String content, String role, String modelName);

    /**
     * 保存带业务附件元数据的聊天消息。
     *
     * @param remark 紧凑的附件元数据 JSON；普通消息传 null
     */
    default void saveChatMessage(Long userId, Long sessionId, String content, String role, String modelName,
                                 String remark) {
        saveChatMessage(userId, sessionId, content, role, modelName);
    }
}
