INSERT INTO sys_user (username, password_hash, is_platform_admin, status, real_name)
SELECT 'admin', '$2a$10$hbOKrho1Jg3wq2FtPcl7oObLEPI0B7n9Iy62nekU5nSFgEx4x3vBG', 1, 1, '平台管理员'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');
