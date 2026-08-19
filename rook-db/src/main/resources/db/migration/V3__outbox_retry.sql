alter table outbox add column next_attempt_at timestamptz not null default 'epoch';

drop index outbox_unpublished;

create index outbox_unpublished on outbox (id) where published_at is null;
create index outbox_retry on outbox (next_attempt_at) where published_at is null;
