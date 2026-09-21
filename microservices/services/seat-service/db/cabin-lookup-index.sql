-- Run against airline_seat_db. Safe to reapply; Hibernate creates the same index on fresh databases.
SET @index_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'flight_instance_cabin'
    AND index_name = 'idx_flight_instance_cabin_flight_class');
SET @index_sql = IF(@index_exists = 0,
  'ALTER TABLE flight_instance_cabin ADD INDEX idx_flight_instance_cabin_flight_class (flight_instance_id, cabin_class_id), ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT 1');
PREPARE cabin_index_stmt FROM @index_sql;
EXECUTE cabin_index_stmt;
DEALLOCATE PREPARE cabin_index_stmt;
