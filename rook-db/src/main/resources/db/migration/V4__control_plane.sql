create table policy (
    id uuid primary key,
    installation_id uuid not null references installation (id) on delete cascade,
    repository_id uuid references repository (id) on delete cascade,
    version integer not null check (version >= 1),
    rules jsonb not null,
    created_at timestamptz not null default now(),
    unique nulls not distinct (installation_id, repository_id, version)
);

create table policy_decision (
    id bigint generated always as identity primary key,
    job_id uuid not null references migration_job (id) on delete cascade,
    outcome text not null check (outcome in ('ALLOW', 'REQUIRE_APPROVAL', 'DENY')),
    reasons jsonb not null default '[]'::jsonb,
    policy_version integer,
    created_at timestamptz not null default now()
);

create index policy_decision_job on policy_decision (job_id, id desc);

create table approval (
    id uuid primary key,
    job_id uuid not null unique references migration_job (id) on delete cascade,
    status text not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    reasons jsonb not null default '[]'::jsonb,
    requested_at timestamptz not null default now(),
    decided_by text,
    decided_at timestamptz,
    note text,
    check ((status = 'PENDING') = (decided_at is null))
);

create index approval_pending on approval (requested_at) where status = 'PENDING';

create table audit_log (
    seq bigint generated always as identity primary key,
    at timestamptz not null,
    actor text not null,
    action text not null,
    subject text not null,
    data jsonb not null default '{}'::jsonb,
    prev_hash text not null,
    hash text not null unique
);

create index audit_log_subject on audit_log (subject, seq);

create table budget (
    installation_id uuid primary key references installation (id) on delete cascade,
    monthly_cap_micros bigint not null check (monthly_cap_micros >= 0),
    job_cap_micros bigint not null check (job_cap_micros >= 0),
    updated_at timestamptz not null default now()
);

create table budget_reservation (
    id uuid primary key,
    installation_id uuid not null references installation (id) on delete cascade,
    job_id uuid,
    kind text not null check (kind in ('MODEL', 'SANDBOX')),
    micros bigint not null check (micros >= 0),
    state text not null default 'RESERVED' check (state in ('RESERVED', 'SETTLED', 'RELEASED')),
    created_at timestamptz not null default now()
);

create index budget_reservation_open on budget_reservation (installation_id) where state = 'RESERVED';

create table usage_ledger (
    id bigint generated always as identity primary key,
    installation_id uuid not null references installation (id) on delete cascade,
    job_id uuid,
    kind text not null check (kind in ('MODEL', 'SANDBOX')),
    units bigint not null check (units >= 0),
    cost_micros bigint not null check (cost_micros >= 0),
    at timestamptz not null
);

create index usage_ledger_month on usage_ledger (installation_id, at);
create index usage_ledger_job on usage_ledger (job_id);

create table api_key (
    id uuid primary key,
    installation_id uuid references installation (id) on delete cascade,
    name text not null,
    prefix text not null unique,
    hash text not null,
    scopes text[] not null check (scopes <@ array['READ', 'WRITE', 'ADMIN']),
    created_at timestamptz not null default now(),
    last_used_at timestamptz,
    revoked_at timestamptz
);
