-- 故障诊断 Agent 场景配置（M1）。
-- 仅扩展 agent_info 配置字段，不改变现有 Agent 的运行逻辑。

ALTER TABLE agent_info
    ADD COLUMN scenario_code VARCHAR(32) NOT NULL DEFAULT 'GENERAL_CHAT'
        COMMENT '场景：GENERAL_CHAT/FAULT_DIAGNOSIS',
    ADD COLUMN execution_mode VARCHAR(32) NOT NULL DEFAULT 'SUPERVISOR'
        COMMENT '执行方式：SUPERVISOR/DETERMINISTIC',
    ADD COLUMN evidence_required CHAR(1) NOT NULL DEFAULT '0'
        COMMENT '是否强制生成证据：0否 1是';
