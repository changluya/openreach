CREATE TABLE monitor_request_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id TEXT NOT NULL UNIQUE,
    request_time_ms INTEGER NOT NULL,
    client_ip TEXT,
    method TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    http_status INTEGER NOT NULL,
    success INTEGER NOT NULL,
    latency_ms INTEGER NOT NULL,
    provider TEXT,
    error_code TEXT,
    error_message TEXT,
    payload_truncated INTEGER NOT NULL DEFAULT 0,
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE monitor_request_payload (
    record_id INTEGER PRIMARY KEY,
    request_payload TEXT,
    response_payload TEXT,
    request_bytes INTEGER NOT NULL DEFAULT 0,
    response_bytes INTEGER NOT NULL DEFAULT 0,
    created_at_ms INTEGER NOT NULL,
    FOREIGN KEY(record_id) REFERENCES monitor_request_record(id) ON DELETE CASCADE
);

CREATE INDEX idx_monitor_request_time ON monitor_request_record(request_time_ms DESC);
CREATE INDEX idx_monitor_status_time ON monitor_request_record(success, request_time_ms DESC);
CREATE INDEX idx_monitor_endpoint_time ON monitor_request_record(endpoint, request_time_ms DESC);
CREATE INDEX idx_monitor_http_status_time ON monitor_request_record(http_status, request_time_ms DESC);
