create function audit_log_reject_change() returns trigger as $$
begin
    raise exception 'audit_log is append only';
end;
$$ language plpgsql;

create trigger audit_log_no_change
    before update or delete on audit_log
    for each row execute function audit_log_reject_change();

create trigger audit_log_no_truncate
    before truncate on audit_log
    for each statement execute function audit_log_reject_change();
