-- Repeatable migration: avoid duplicate-index errors on later deployments.
SET @cinemahall_has_index = (
 SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND table_name = 'sync_items'
   AND index_name = 'sync_items_snapshot'
);
SET @cinemahall_create_index = IF(@cinemahall_has_index = 0,
 'CREATE INDEX sync_items_snapshot ON sync_items (account_id,profile_index,entity_type,is_deleted,updated_at)',
 'SELECT 1');
PREPARE cinemahall_index_migration FROM @cinemahall_create_index;
EXECUTE cinemahall_index_migration;
DEALLOCATE PREPARE cinemahall_index_migration;
