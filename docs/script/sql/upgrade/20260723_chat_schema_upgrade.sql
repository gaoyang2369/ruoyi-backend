-- 为已初始化的旧数据库补齐聊天/智能体模块结构。
-- 仅新增字段和表；不会删除或覆盖任何已有业务数据。

-- MySQL 8.0.33 does not support ADD COLUMN IF NOT EXISTS, so check metadata first.
SET @has_model_dimension := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_model'
      AND column_name = 'model_dimension'
);
SET @add_model_dimension_sql := IF(
    @has_model_dimension = 0,
    'ALTER TABLE `chat_model` ADD COLUMN `model_dimension` int NULL DEFAULT NULL COMMENT ''模型维度'' AFTER `model_describe`',
    'SELECT 1'
);
PREPARE add_model_dimension_stmt FROM @add_model_dimension_sql;
EXECUTE add_model_dimension_stmt;
DEALLOCATE PREPARE add_model_dimension_stmt;

CREATE TABLE IF NOT EXISTS `agent_info` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '智能体ID',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户ID',
    `agent_name` varchar(200) NOT NULL COMMENT '智能体名称',
    `agent_describe` varchar(500) DEFAULT NULL COMMENT '智能体描述（下拉展示用）',
    `agent_show` varchar(255) DEFAULT NULL COMMENT '展示图标/头像URL',
    `model_id` bigint NOT NULL COMMENT '绑定的聊天模型ID（chat_model.id, category=chat）',
    `enable_thinking` char(1) DEFAULT '0' COMMENT '是否启用深度思考：0 否 1 是',
    `system_prompt` text DEFAULT NULL COMMENT '自定义系统提示词',
    `mcp_tool_ids` varchar(1024) DEFAULT NULL COMMENT '关联MCP工具ID列表（JSON数组）',
    `skill_names` varchar(1024) DEFAULT NULL COMMENT '关联磁盘技能名列表（JSON数组）',
    `knowledge_ids` varchar(1024) DEFAULT NULL COMMENT '关联知识库ID列表（JSON数组）',
    `status` char(1) DEFAULT '0' COMMENT '状态：0 正常 1 停用',
    `remark` varchar(500) DEFAULT NULL COMMENT '备注',
    `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
    `create_by` bigint DEFAULT NULL COMMENT '创建者',
    `create_time` datetime DEFAULT NULL COMMENT '创建时间',
    `update_by` bigint DEFAULT NULL COMMENT '更新者',
    `update_time` datetime DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_agent_tenant_id` (`tenant_id`),
    KEY `idx_agent_model_id` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体信息表';

-- 模型管理页面使用的预置字典。旧库中缺失时，分类和计费方式下拉框会为空。
INSERT INTO `sys_dict_type`
    (`dict_id`, `tenant_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
VALUES
    (2026642112982360066, '000000', '模型分类', 'chat_model_category', 103, 1, NOW(), 1, NOW(), '模型分类'),
    (2026642183606050817, '000000', '计费方式', 'sys_model_billing', 103, 1, NOW(), 1, NOW(), '计费方式')
ON DUPLICATE KEY UPDATE
    `dict_name` = VALUES(`dict_name`), `update_by` = VALUES(`update_by`), `update_time` = VALUES(`update_time`);

INSERT INTO `sys_dict_data`
    (`dict_code`, `tenant_id`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
VALUES
    (2026642472673288194, '000000', 0, '对话', 'chat', 'chat_model_category', NULL, 'cyan', 'N', 103, 1, NOW(), 1, NOW(), NULL),
    (2026642525081116674, '000000', 1, '图像', 'image', 'chat_model_category', NULL, 'success', 'N', 103, 1, NOW(), 1, NOW(), NULL),
    (2027261114955931650, '000000', 2, '向量', 'vector', 'chat_model_category', NULL, 'default', 'N', 103, 1, NOW(), 1, NOW(), NULL),
    (2045070879435259905, '000000', 4, '重排序', 'rerank', 'chat_model_category', NULL, '#000000', 'N', 103, 1, NOW(), 1, NOW(), '重排序模型'),
    (2026643983713247233, '000000', 1, '次数计费', '1', 'sys_model_billing', NULL, 'green', 'N', 103, 1, NOW(), 1, NOW(), NULL),
    (2026644058522853378, '000000', 2, 'Token计费', '2', 'sys_model_billing', NULL, 'primary', 'N', 103, 1, NOW(), 1, NOW(), NULL)
ON DUPLICATE KEY UPDATE
    `dict_sort` = VALUES(`dict_sort`), `dict_label` = VALUES(`dict_label`), `dict_value` = VALUES(`dict_value`),
    `dict_type` = VALUES(`dict_type`), `update_by` = VALUES(`update_by`), `update_time` = VALUES(`update_time`),
    `remark` = VALUES(`remark`);

-- 新版管理员前端的“智能体管理”页面是动态路由：除了前端代码，还必须有这些菜单数据。
-- 旧数据库缺少它们时，侧栏不会显示入口。
INSERT INTO `sys_menu`
    (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
VALUES
    (3000, '智能体管理', 0, 1, 'agent', '', '', 1, 0, 'M', '0', '0', '', 'mdi:robot', 103, 1, NOW(), NULL, NULL, '智能体管理目录'),
    (3001, '智能体列表', 3000, 1, 'agent', 'agent/agent/index', '', 1, 0, 'C', '0', '0', 'agent:agent:list', 'mdi:robot-outline', 103, 1, NOW(), NULL, NULL, '智能体列表菜单'),
    (3002, '智能体查询', 3001, 1, '#', '', '', 1, 0, 'F', '0', '0', 'agent:agent:query', '#', 103, 1, NOW(), NULL, NULL, ''),
    (3003, '智能体新增', 3001, 2, '#', '', '', 1, 0, 'F', '0', '0', 'agent:agent:add', '#', 103, 1, NOW(), NULL, NULL, ''),
    (3004, '智能体修改', 3001, 3, '#', '', '', 1, 0, 'F', '0', '0', 'agent:agent:edit', '#', 103, 1, NOW(), NULL, NULL, ''),
    (3005, '智能体删除', 3001, 4, '#', '', '', 1, 0, 'F', '0', '0', 'agent:agent:remove', '#', 103, 1, NOW(), NULL, NULL, ''),
    (3006, '智能体导出', 3001, 5, '#', '', '', 1, 0, 'F', '0', '0', 'agent:agent:export', '#', 103, 1, NOW(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE
    `menu_name` = VALUES(`menu_name`), `parent_id` = VALUES(`parent_id`), `order_num` = VALUES(`order_num`),
    `path` = VALUES(`path`), `component` = VALUES(`component`), `perms` = VALUES(`perms`),
    `icon` = VALUES(`icon`), `status` = VALUES(`status`), `update_time` = NOW(), `remark` = VALUES(`remark`);

-- 同时授予管理员角色。超级管理员会自动看到所有正常菜单；此项也兼容角色授权模式。
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES
    (1, 3000), (1, 3001), (1, 3002), (1, 3003), (1, 3004), (1, 3005), (1, 3006);
