# Local PostgreSQL development

This setup is only for local compatibility checks. It does not connect to Render or Supabase and does not change production settings.

## Purpose

- Keep H2 as the fast default test database.
- Add a Docker PostgreSQL database for SQL compatibility checks.
- Detect PostgreSQL failures before using the Render/Supabase environment.

## Start PostgreSQL

```powershell
docker compose -f docker-compose.postgres.yml up -d
```

The local database is exposed on `localhost:5433` to avoid conflicts with an existing PostgreSQL server.

## Run the app with PostgreSQL

```powershell
powershell -ExecutionPolicy Bypass -File .\run-dev-postgres.ps1
```

Equivalent environment variables:

```powershell
$env:JDBC_URL="jdbc:postgresql://localhost:5433/shiftflow_dev"
$env:DB_USER="shiftflow"
$env:DB_PASSWORD="shiftflow_dev_password"
$env:BASE_SEED="true"
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1
```

Do not use Supabase production credentials for this local environment.

## Stop PostgreSQL

Keep data:

```powershell
docker compose -f docker-compose.postgres.yml down
```

Reset local data:

```powershell
docker compose -f docker-compose.postgres.yml down -v
```

The reset command deletes only the local Docker volume named by this compose file.

## PostgreSQL compatibility smoke test

```powershell
powershell -ExecutionPolicy Bypass -File .\test-postgres.ps1 -ResetDatabase
```

The default smoke test runs `service.ShiftPreferenceWorkflowTest` against PostgreSQL. If it fails on SQL syntax, treat that as a compatibility issue to fix in a focused follow-up branch.

Current confirmed result:

```text
powershell -ExecutionPolicy Bypass -File .\test-postgres.ps1 -ResetDatabase
ShiftPreferenceWorkflowTest: all checks passed
```

To run specific tests:

```powershell
powershell -ExecutionPolicy Bypass -File .\test-postgres.ps1 -ResetDatabase -Tests service.ShiftPreferenceWorkflowTest,service.ShiftWorkflowTest
```

## H2 vs PostgreSQL

- Use `test.ps1` for the normal H2 test suite.
- Use `run-dev-postgres.ps1` for manual PostgreSQL checks.
- Use `test-postgres.ps1` for targeted PostgreSQL compatibility checks.

## Features to verify on PostgreSQL

- Login
- Dashboard
- Shift preference submission and resubmission
- Shift management save/update
- Attendance clock-in/clock-out
- Leave request, approval, and balance display
- Auto shift assignment
- Notifications and mail outbox
- Month-based dashboard and date aggregation

## Known remaining compatibility work

`service.AttendanceService` still contains an H2-style `MERGE INTO attendance ... KEY(user_id,work_date)` statement. It was not reached by the default `ShiftPreferenceWorkflowTest` PostgreSQL smoke test. Verify and fix it in a focused follow-up if attendance clock-in/out is added to the PostgreSQL compatibility test path.
