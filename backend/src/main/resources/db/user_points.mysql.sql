-- 用户积分日配额与管理员加项（与 spring.jpa.hibernate.ddl-auto=none 配合，部署后手动执行）。
-- 自然日按上海时区 Asia/Shanghai 计算，与 {@link top.uigpt.service.PointsService} 一致。
-- 若尚无 users.privilege，可先执行本文件再执行 user_privilege.mysql.sql；points 放在 status 后，避免依赖 privilege 列已存在。
-- users.points：当前可用积分（扣费递减）。
-- users.points_bonus：管理员可编辑；每个上海新自然日开始时将 points 重置为「角色日上限 + points_bonus」。
-- users.points_refill_date：上次按上海日历执行日重置的日期；NULL 表示尚未按新日规则初始化。
-- 若列已存在，对应语句会报错，可忽略或改用 docs/migrate-incremental-columns.sql（可重复执行）。

ALTER TABLE users
    ADD COLUMN points INT NOT NULL DEFAULT 0 COMMENT '当前可用积分' AFTER status;

ALTER TABLE users
    ADD COLUMN points_bonus INT NOT NULL DEFAULT 0 COMMENT '管理员日配额加项' AFTER points,
    ADD COLUMN points_refill_date DATE NULL COMMENT '上海日历：上次日重置' AFTER points_bonus;
