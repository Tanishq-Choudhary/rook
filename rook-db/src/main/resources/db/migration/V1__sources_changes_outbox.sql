create table watched_source (
    id text primary key,
    kind text not null check (kind in ('OPENAPI', 'MAVEN', 'RELEASE_FEED')),
    display_name text not null,
    location text not null,
    config jsonb not null default '{}'::jsonb,
    etag text,
    last_version text,
    last_checked_at timestamptz,
    last_error text,
    poll_interval_seconds integer not null default 900 check (poll_interval_seconds >= 30),
    enabled boolean not null default true,
    created_at timestamptz not null default now()
);

create table source_snapshot (
    id uuid primary key,
    source_id text not null references watched_source (id) on delete cascade,
    version text not null,
    content_hash text not null,
    content_gz bytea not null,
    fetched_at timestamptz not null default now(),
    unique (source_id, version),
    unique (source_id, content_hash)
);

create table change_set (
    id uuid primary key,
    source_id text not null references watched_source (id),
    change_key text not null unique,
    from_version text not null,
    to_version text not null,
    highest_severity text not null check (highest_severity in ('ADVISORY', 'ADDITIVE', 'DEPRECATION', 'BREAKING')),
    requires_migration boolean not null,
    notes jsonb not null default '[]'::jsonb,
    detected_at timestamptz not null,
    replay_count integer not null default 0
);

create index change_set_source_detected on change_set (source_id, detected_at desc);

create table change_event (
    id uuid primary key,
    change_set_id uuid not null references change_set (id) on delete cascade,
    fingerprint text not null unique,
    kind text not null,
    severity text not null check (severity in ('ADVISORY', 'ADDITIVE', 'DEPRECATION', 'BREAKING')),
    subject_kind text not null,
    subject_name text not null,
    attributes jsonb not null default '{}'::jsonb,
    summary text not null
);

create index change_event_set on change_event (change_set_id);
create index change_event_subject on change_event (subject_kind, subject_name);

create table outbox (
    id bigint generated always as identity primary key,
    topic text not null,
    message_key text not null,
    payload jsonb not null,
    headers jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    published_at timestamptz,
    attempts integer not null default 0,
    last_error text
);

create index outbox_unpublished on outbox (id) where published_at is null;

create table processed_message (
    consumer text not null,
    message_id text not null,
    processed_at timestamptz not null default now(),
    primary key (consumer, message_id)
);
