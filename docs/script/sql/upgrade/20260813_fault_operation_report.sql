-- 运行报告结构化快照。预览、Markdown 与浏览器打印只能读取 report_json，不得重新查询遥测。
CREATE TABLE IF NOT EXISTS `fault_operation_report` (
    `id` bigint NOT NULL COMMENT '主键',
    `report_code` varchar(64) NOT NULL COMMENT '报告编号',
    `session_id` bigint NULL DEFAULT NULL COMMENT '聊天会话ID',
    `user_id` bigint NOT NULL COMMENT '报告所属用户',
    `device_name` varchar(128) NOT NULL COMMENT '设备名称',
    `inverter_name` varchar(128) NULL DEFAULT NULL COMMENT '变频器名称',
    `window_start` datetime NOT NULL COMMENT '请求分析开始时间',
    `window_end` datetime NOT NULL COMMENT '请求分析结束时间',
    `report_status` varchar(20) NOT NULL COMMENT '报告状态',
    `period_status` varchar(20) NOT NULL COMMENT '分析周期状态',
    `current_status` varchar(20) NOT NULL COMMENT '当前状态',
    `report_json` longtext NOT NULL COMMENT '完整 OperationReportResult JSON',
    `create_dept` bigint NULL DEFAULT NULL COMMENT '创建部门',
    `create_by` bigint NULL DEFAULT NULL COMMENT '创建者',
    `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
    `update_by` bigint NULL DEFAULT NULL COMMENT '更新者',
    `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
    `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_fault_report_tenant_code` (`tenant_id`, `report_code`),
    KEY `idx_fault_report_owner` (`tenant_id`, `user_id`, `session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='运行报告快照';
