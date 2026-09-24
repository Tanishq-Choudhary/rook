create table webhook_delivery (
    id text primary key,
    event text not null,
    received_at timestamptz not null default now(),
    outcome text
);

create index webhook_delivery_received on webhook_delivery (received_at);

create table pull_request (
    id uuid primary key,
    job_id uuid references migration_job (id) on delete set null,
    repository_id uuid not null references repository (id) on delete cascade,
    number integer not null check (number >= 1),
    url text not null,
    branch text not null,
    head_sha text not null,
    state text not null default 'OPEN' check (state in ('OPEN', 'CLOSED', 'MERGED')),
    created_at timestamptz not null default now(),
    closed_at timestamptz,
    unique (repository_id, number),
    unique (repository_id, branch)
);

create index pull_request_job on pull_request (job_id);
