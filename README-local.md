# Run PetClinic locally with Docker Compose

For GitHub Actions and an Ubuntu VM runner, see [README-cicd.md](README-cicd.md).

## What runs

This project starts eight containers: PetClinic (including its browser UI),
MySQL, three Redis servers, and three Sentinels. Redis-0 starts as the primary;
Redis-1 and Redis-2 start as replicas. Sentinel can promote a replica if the
primary fails. PetClinic discovers the current primary through Sentinel.

The browser connects at http://localhost:8000/. MySQL, Redis, and Sentinel have
no published host ports. This is a local learning setup, not a production
deployment: the original Redis 4 image is outdated and Redis has no password.

## Before starting

1. Start Docker Desktop and wait until its Linux engine is running.
2. Open Git Bash in this project folder.
3. Keep the existing `.env`; its variable names have been corrected. For a new
   checkout only, copy `.env.example` to `.env`, fill in both password values,
   and run `docker volume create petclinic-mysql-data` once.
4. Keep real passwords in `.env`, not in Compose or Git. Compose passes the
   same application password to MySQL and PetClinic. Do not print the fully
   resolved Compose configuration when sharing output because it contains them.

## Start and check

```bash
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 240
docker compose ps
curl -fsS http://localhost:8000/actuator/health
```

The first build downloads Java dependencies, builds the application JAR, and
runs its Maven tests. You do not need Java or Maven installed on your laptop.
The build's default tests use H2 and the original local cache, so they do not
need the Compose database or Redis. Runtime enables the `mysql,redis` profiles.

Open http://localhost:8000/ in your browser. Try finding an owner and opening
the Veterinarians page. The UI is served by the same Java application.

## Verify replication and Sentinel

```bash
docker compose exec redis-0 redis-cli INFO replication
docker compose exec redis-1 redis-cli INFO replication
docker compose exec redis-2 redis-cli INFO replication
docker compose exec sentinel-0 redis-cli -p 26379 SENTINEL ckquorum mymaster
docker compose exec sentinel-0 redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster
```

Initially Redis-0 should report `role:master` and two connected replicas. The
other Redis servers should report `role:slave` and `master_link_status:up`.
After failover, the primary may be a different server; ask Sentinel which one.

Open the Veterinarians page twice to exercise the Redis cache. On the current
primary, `redis-cli --scan --pattern 'vets::*'` shows cached entry names without
printing their contents. Entries expire after ten minutes.

## Configuration files

Each Redis has a real file in `redis-0/redis.conf`, `redis-1/redis.conf`, or
`redis-2/redis.conf`. Each Sentinel has a file in `sentinel-0/sentinel.conf`,
`sentinel-1/sentinel.conf`, or `sentinel-2/sentinel.conf`.

Compose mounts each folder at `/etc/redis` inside that container. These mounts
are writable because Redis and Sentinel save topology changes after failover.
Redis and Sentinel announce Docker service names so their saved topology does
not depend on temporary container IP addresses.
Do not share one writable configuration between several instances. The old
empty folders named `redis.conf` and `sentinel.conf` are no longer used.

## Troubleshooting without deleting data

```bash
docker compose ps
docker compose logs --tail=100 mysql petclinic-api
docker compose logs --tail=100 redis-0 redis-1 redis-2 sentinel-0 sentinel-1 sentinel-2
```

MySQL reuses the existing data volume selected by `MYSQL_DATA_VOLUME` in `.env`.
It is declared external so Compose does not own or delete it. The old named
volume `petclinic-msql` held MySQL configuration, not database data; it is left
untouched. Editing `.env` does not
change users or passwords in an already initialized database. If MySQL reports
`Access denied`, use that database's existing credentials or plan an explicit
password change; do not delete its volume to hide the error. If an older
container used a different data volume, preserve it and identify the correct
one before switching storage. The unused old API volume is not deleted either.

Use `docker compose stop` to stop the stack without removing containers or data.
Do not use `docker compose down -v` unless you intend to erase the database.
