-- Run against airline_booking_db before deploying the matching Ticket mapping.
-- Ticket numbers identify issued tickets globally (the generator already checks this).
-- Preflight reports only aggregate counts, never passenger or ticket identifiers.
SELECT COUNT(*) AS duplicate_groups, COALESCE(SUM(n - 1), 0) AS duplicate_rows
FROM (SELECT COUNT(*) AS n FROM tickets GROUP BY ticket_number HAVING COUNT(*) > 1) duplicates;

-- The UNIQUE build itself rechecks all rows, including concurrent inserts.
-- If duplicates exist it fails without deleting/renumbering tickets. Investigate those
-- separately; do not force execution past an error or silently fall back to non-unique.
-- Accept any existing full-column, single-column unique index with this definition.
SET @ticket_index_exists = (SELECT COUNT(*) FROM (
  SELECT index_name FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'tickets'
  GROUP BY index_name
  HAVING MAX(non_unique) = 0 AND COUNT(*) = 1
    AND MAX(column_name) = 'ticket_number' AND MAX(sub_part) IS NULL
) matching_indexes);
SET SESSION lock_wait_timeout = 5;
SET @ticket_index_sql = IF(@ticket_index_exists = 0,
  'ALTER TABLE tickets ADD UNIQUE INDEX uk_tickets_ticket_number (ticket_number), ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT ''Ticket number unique index already exists'' AS result');
PREPARE ticket_index_stmt FROM @ticket_index_sql;
EXECUTE ticket_index_stmt;
DEALLOCATE PREPARE ticket_index_stmt;
