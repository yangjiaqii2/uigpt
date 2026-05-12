-- 用户角色列：与 ddl-auto=none 配合，请在部署后手动执行。
-- privilege: 0=普通用户(STANDARD) 1=付费用户(PREMIUM) 2=超级管理员(SUPER_ADMIN)
-- 超级管理员与 UIGPT_ADMIN_USERNAMES（uigpt.admin.usernames-csv）共同决定 /api/me 的 admin 与后台访问，见 AdminAuthorizationService。

ALTER TABLE users
    ADD COLUMN privilege TINYINT NOT NULL DEFAULT 0 COMMENT '0普通 1付费 2超级管理' AFTER status;

-- 回填：把环境变量名单中的账号标为超级管理员（IN 列表请改为与 users.username 一致的实际值；其余行已由 DEFAULT 0）。
-- UPDATE users SET privilege = 2 WHERE username IN ('your_admin_username');
