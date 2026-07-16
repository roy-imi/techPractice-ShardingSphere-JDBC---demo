-- 主库复制账号只需要复制相关权限，不授予业务表写权限。
-- 密码与 docker-compose.yml 中 replication-setup 的教学默认值保持一致。
-- 生产环境应把密码放入 Secret Manager，并限制成明确的副本地址，而不是 '%'。
CREATE USER IF NOT EXISTS 'replicator'@'%'
    IDENTIFIED WITH caching_sha2_password BY 'replication-password';

-- 允许重复初始化时把密码恢复为 Compose 约定值。
ALTER USER 'replicator'@'%'
    IDENTIFIED WITH caching_sha2_password BY 'replication-password';

GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'replicator'@'%';
FLUSH PRIVILEGES;
