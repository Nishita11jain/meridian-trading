# Running the stack (Windows PowerShell)

## Prerequisites and first-time setup

- JDK 21+, Maven, Node 22+, PostgreSQL (psql on the PATH), a reachable Kafka broker (`infrastructure/kafka/scripts/create-topics.sh`), bash for the DB scripts.
- Copy `.env.example` to `.env` at the repository root and fill in `POSTGRES_PASSWORD`, `JWT_SECRET` and `FAUXNANCE_API_KEY`. Keep both files on the same keys. `config/application.yml` documents how each value is used.
- Create the database named in `POSTGRES_DB`, then `bash databases/postgresql/apply.sh` (migrations, seed data, demo logins; password `Meridian#2026`). `databases/postgresql/schema.sql` + `seed-data.sql` are an equivalent one-shot alternative, and `reset.sql` wipes everything.

Run each block from the repository root, in its own window. Kafka and Postgres must already be up.

## Load `.env` (every Java window)

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#=\s][^=]*)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim().Trim('"'), 'Process') }
}
```

## Database (first time)

```powershell
bash databases/postgresql/apply.sh
```

## Executor-service (8082)

```powershell
cd services\executor-service
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"
```

## Order-service (8081)

```powershell
cd services\order-service
mvn spring-boot:run
```

## Auth-service (3000)

```powershell
cd services\auth-service
npm run start:dev
```

## Frontend (4200)

```powershell
cd frontend
npm start
```
