create table impact_report (
    job_id uuid primary key references migration_job (id) on delete cascade,
    decision text not null check (decision in ('NO_IMPACT', 'REVIEW_ONLY', 'NEEDS_MIGRATION')),
    summary text not null,
    base_sha text not null,
    report jsonb not null,
    created_at timestamptz not null default now()
);

create table patch_proposal (
    id uuid primary key,
    job_id uuid not null references migration_job (id) on delete cascade,
    attempt integer not null check (attempt >= 0),
    strategy text not null,
    status text not null,
    summary text,
    confidence numeric(4, 3),
    diff text not null,
    files jsonb not null default '{}'::jsonb,
    deletions jsonb not null default '[]'::jsonb,
    unresolved jsonb not null default '[]'::jsonb,
    usage jsonb not null default '{}'::jsonb,
    cost_micros bigint not null default 0 check (cost_micros >= 0),
    trace jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now(),
    unique (job_id, attempt)
);

create table verification_run (
    id uuid primary key,
    job_id uuid not null references migration_job (id) on delete cascade,
    attempt integer not null check (attempt >= 0),
    passed boolean not null,
    supported boolean not null,
    summary text not null,
    baseline_run integer,
    baseline_failed integer,
    patched_run integer,
    patched_failed integer,
    report jsonb not null,
    elapsed_millis bigint not null check (elapsed_millis >= 0),
    created_at timestamptz not null default now(),
    unique (job_id, attempt)
);
