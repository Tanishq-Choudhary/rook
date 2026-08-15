create table installation (
    id uuid primary key,
    github_installation_id bigint unique,
    account_login text not null,
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED', 'REMOVED')),
    created_at timestamptz not null default now()
);

create table repository (
    id uuid primary key,
    installation_id uuid not null references installation (id) on delete cascade,
    github_repo_id bigint unique,
    full_name text not null,
    default_branch text not null default 'main',
    enabled boolean not null default true,
    last_indexed_sha text,
    created_at timestamptz not null default now(),
    unique (installation_id, full_name)
);

create table repository_watch (
    repository_id uuid not null references repository (id) on delete cascade,
    source_id text not null references watched_source (id) on delete cascade,
    primary key (repository_id, source_id)
);

create table migration_job (
    id uuid primary key,
    change_set_id uuid not null references change_set (id),
    repository_id uuid not null references repository (id),
    state text not null check (state in (
        'PENDING', 'INDEXING', 'PROPOSING', 'VERIFYING', 'AWAITING_APPROVAL', 'PUBLISHING',
        'COMPLETED', 'NO_IMPACT', 'REJECTED', 'FAILED', 'DEAD', 'SUPERSEDED', 'CANCELLED')),
    attempt integer not null default 0,
    max_attempts integer not null default 5 check (max_attempts >= 1),
    priority integer not null default 0,
    next_run_at timestamptz not null default now(),
    lease_owner text,
    lease_expires_at timestamptz,
    fence bigint not null default 0,
    last_error text,
    checkpoint jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    finished_at timestamptz,
    unique (change_set_id, repository_id),
    check ((lease_owner is null) = (lease_expires_at is null))
);

create index migration_job_claimable on migration_job (priority desc, next_run_at)
    where state in ('PENDING', 'INDEXING', 'PROPOSING', 'VERIFYING', 'PUBLISHING');

create index migration_job_repository on migration_job (repository_id, created_at desc);

create unique index migration_job_one_running_per_repository
    on migration_job (repository_id)
    where state in ('INDEXING', 'PROPOSING', 'VERIFYING', 'PUBLISHING');

create table job_event (
    id bigint generated always as identity primary key,
    job_id uuid not null references migration_job (id) on delete cascade,
    at timestamptz not null default now(),
    kind text not null,
    state text,
    data jsonb not null default '{}'::jsonb
);

create index job_event_job on job_event (job_id, id);

create table dead_letter (
    id uuid primary key,
    job_id uuid references migration_job (id) on delete set null,
    source text not null,
    reason text not null,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    resolved_at timestamptz
);

create index dead_letter_open on dead_letter (created_at) where resolved_at is null;
