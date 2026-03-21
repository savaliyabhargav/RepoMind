CREATE TABLE file_node_dependencies (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_file_id      UUID NOT NULL REFERENCES file_nodes(id) ON DELETE CASCADE,
    target_file_id      UUID NOT NULL REFERENCES file_nodes(id) ON DELETE CASCADE,
    relationship_type   VARCHAR(20) NOT NULL,

    UNIQUE(source_file_id, target_file_id, relationship_type)
);

CREATE INDEX idx_file_deps_source ON file_node_dependencies(source_file_id);
CREATE INDEX idx_file_deps_target ON file_node_dependencies(target_file_id);