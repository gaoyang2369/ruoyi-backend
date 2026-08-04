-- 运行报告接口权限。报告查看/下载接口由 @SaCheckPermission 保护，
-- 需要为角色授权后才能访问（超级管理员通常自动具备全部权限）。
INSERT INTO `sys_menu`
    (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
VALUES
    (3100, '运行报告查询', 3001, 6, '#', '', '', 1, 0, 'F', '0', '0', 'fault:report:query', '#', 103, 1, NOW(), NULL, NULL, ''),
    (3101, '运行报告导出', 3001, 7, '#', '', '', 1, 0, 'F', '0', '0', 'fault:report:export', '#', 103, 1, NOW(), NULL, NULL, '')
ON DUPLICATE KEY UPDATE
    `menu_name` = VALUES(`menu_name`), `perms` = VALUES(`perms`),
    `update_time` = NOW(), `remark` = VALUES(`remark`);

-- 同时授予管理员角色。超级管理员会自动看到所有正常菜单；此项也兼容角色授权模式。
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES
    (1, 3100), (1, 3101);
