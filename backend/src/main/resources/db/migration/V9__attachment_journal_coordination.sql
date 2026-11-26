-- U08: additive database-scoped attachment journal coordination.
CREATE TABLE attachment_journal_identity (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    namespace UUID NOT NULL UNIQUE
);
INSERT INTO attachment_journal_identity (id, namespace) VALUES (1, gen_random_uuid());
CREATE TABLE attachment_journal_locks (bucket INTEGER PRIMARY KEY CHECK (bucket BETWEEN 0 AND 31));
INSERT INTO attachment_journal_locks (bucket) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15),(16),(17),(18),(19),(20),(21),(22),(23),(24),(25),(26),(27),(28),(29),(30),(31);
