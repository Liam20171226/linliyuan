-- 公告 Banner 顺序：1~5，非空即上首页 Banner 且按该顺序展示
ALTER TABLE notice ADD COLUMN banner_order INT NULL;

UPDATE notice SET banner_order = NULL WHERE banner_order IS NOT NULL AND (banner_order < 1 OR banner_order > 5);
