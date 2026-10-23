# PetClinic CI/CD with GitHub Container Registry

## What this prepares

```text
Push to main -> GitHub-hosted runner builds and tests -> GHCR stores image
             -> your Ubuntu VM runner pulls that exact image -> Compose starts PetClinic
```

The image is named `ghcr.io/<github-owner>/kunballi-petclinic`. Each successful
build publishes `sha-<full-commit-id>` and `latest` tags. Deployment uses the
image digest, not the moving `latest` tag, so it runs exactly what was built.
The existing Dockerfile runs the Maven tests before creating the final image.
Pull requests run build/tests only, on a GitHub-hosted runner, without publishing
an image or running deployment on your VM.

No VM is created or registered by these files. Deployment is disabled until
you explicitly set `PETCLINIC_DEPLOY_ENABLED` to `true` in GitHub.
The project folder also needs to be connected and pushed to a GitHub repository;
creating workflow files alone does not connect it to GitHub.

## 1. Prepare a dedicated Ubuntu VM

Use Ubuntu Linux x64 (not ARM) with Docker Engine, the Docker Compose plugin,
Git, and curl installed. Follow the official [Docker Ubuntu installation guide](https://docs.docker.com/engine/install/ubuntu/).
The runner user must be able to run `docker info` without sudo. Docker access
is effectively root-level access: dedicate this VM to your own trusted repository.

Keep the repository private when using this persistent self-hosted runner.
Do not run untrusted pull request workflows on the VM. Protect `main` and review
changes to workflow files. A runner label routes jobs; it is not a security boundary.
GitHub explains these risks in its [self-hosted runner guide](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/add-runners).

The following are setup instructions for you to run on the VM, not commands
executed automatically by this project.

## 2. Register the VM as a runner

In your private GitHub repository, open Settings -> Actions -> Runners ->
New self-hosted runner. Select Linux and x64. Run the current download and
registration commands GitHub displays, as a non-root user on the VM.
Use `kunballi-petclinic-runner` as the runner name and add the custom label
`kunballi-petclinic`. Keep the default `self-hosted`, `linux`, and `x64` labels.

Install/start the runner as a service using GitHub's displayed instructions,
and confirm it shows Idle in the repository settings. Keep the runner software
in its own folder, such as `~/actions-runner`, separate from the application.
Never put its registration token, `.runner`, or `.credentials` files in this
project or share them in chat.

## 3. Prepare the application folder once

Keep the deployed application outside `actions-runner/_work`, which GitHub may
clean between jobs. These commands assume the logged-in user is the runner user.
Replace `YOUR_PRIVATE_REPOSITORY_URL` before running the clone command, using
your normal secure Git authentication; never embed a token in the URL.

```bash
sudo mkdir -p /opt/kunballi-petclinic
sudo chown "$(id -un):$(id -gn)" /opt/kunballi-petclinic
git clone YOUR_PRIVATE_REPOSITORY_URL /opt/kunballi-petclinic
cd /opt/kunballi-petclinic
cp .env.example .env
chmod 600 .env
nano .env
```

Only copy `.env.example` for a new VM checkout. Do not overwrite a working
`.env` or copy the laptop's anonymous database volume ID to the VM. In the new
VM's `.env`, set your own `MYSQL_ROOT_PASSWORD` and `MYSQL_PASSWORD`; keep the
application's user/database consistent and use `MYSQL_DATA_VOLUME=petclinic-mysql-data`.
Passwords stay on the VM and are not uploaded to GitHub or embedded in the image.
Then, for this new database only:

```bash
docker volume create petclinic-mysql-data
docker compose config --quiet
```

Keep all six folders (`redis-0`, `redis-1`, `redis-2`, `sentinel-0`, `sentinel-1`,
`sentinel-2`) and their real `.conf` files in this VM checkout. They must be
writable by the container users: Redis and Sentinel rewrite configuration when
topology changes. A normal Git checkout belongs to your Ubuntu user, which may
not match the Redis image's user. On this dedicated VM, determine the image
users and give them ownership of their own configuration directories/files:

```bash
docker pull redis:4.0.2
docker pull redis:alpine
REDIS_UID="$(docker run --rm --entrypoint id redis:4.0.2 -u redis)"
REDIS_GID="$(docker run --rm --entrypoint id redis:4.0.2 -g redis)"
SENTINEL_UID="$(docker run --rm --entrypoint id redis:alpine -u redis)"
SENTINEL_GID="$(docker run --rm --entrypoint id redis:alpine -g redis)"
for index in 0 1 2; do
  sudo chown "$REDIS_UID:$REDIS_GID" "redis-$index" "redis-$index/redis.conf"
  sudo chown "$SENTINEL_UID:$SENTINEL_GID" "sentinel-$index" "sentinel-$index/sentinel.conf"
done
```

Run these only from `/opt/kunballi-petclinic` on the VM, not on the laptop or
someone else's deployment. Do not make everything world-writable.
See README-local.md for what each configuration does.

This creates a fresh VM database; it does not migrate records from your laptop.
If the VM already has PetClinic data, identify its existing volume and credentials
instead of creating/repointing storage blindly. Do not use `down -v` or delete volumes.

## 4. Enable deployment in GitHub

Create a GitHub environment named `staging` and restrict deployments to `main`.
For fully automatic deployment after a push, do not require an extra manual
environment approval; add required reviewers instead if you prefer an approval gate.

Under Settings -> Secrets and variables -> Actions -> Variables, add:

| Name | Value | Purpose |
| --- | --- | --- |
| `PETCLINIC_DEPLOY_ENABLED` | `true` | Enable the VM deploy job only after setup is complete. |
| `PETCLINIC_DEPLOY_DIR` | `/opt/kunballi-petclinic` | Optional; this is already the default folder. |

No manually created registry token or SSH key secret is needed for this design.
GitHub supplies `GITHUB_TOKEN`; the build job has package-write permission and
the deployment job has package-read permission. The self-hosted runner executes
commands directly on the VM, so deployment does not need to SSH into it.
The GHCR package must grant this repository access; a package with the same name
previously published from another repository may need its access settings corrected.

## 5. Trigger and check the pipeline

Push a reviewed commit to `main`, or open Actions -> Kunballi PetClinic CI/CD ->
Run workflow and choose `main`. Check the build job first, then the deploy job.
If deployment is skipped, check `PETCLINIC_DEPLOY_ENABLED`. If it stays queued,
check that the VM runner is online and has all four required labels.

On the VM, inspect the running stack without printing secrets:

```bash
cd /opt/kunballi-petclinic
APP_IMAGE="$(cat .deployed-image)" docker compose ps
curl -fsS http://127.0.0.1:8000/actuator/health
```

The workflow leaves MySQL's external volume in place. It does not automatically
pull new Compose files, overwrite Redis/Sentinel runtime configuration, update
database passwords, or delete data. Review and apply infrastructure/configuration
changes separately. Changes to Java code and templates go into the new image.

The app is bound to the VM's loopback address, not the public internet. To view
it from your laptop without exposing the legacy app publicly, open an SSH tunnel
in Git Bash, replacing `VM_USER` and `VM_HOST` with your own details:

```bash
ssh -L 8000:127.0.0.1:8000 VM_USER@VM_HOST
```

Keep that session open and browse to http://127.0.0.1:8000/ on your laptop.
Stop the laptop's local PetClinic first or choose another laptop port if 8000
is already occupied. Do not publish MySQL, Redis, or Sentinel ports.

## 6. Failed deployments and rollback

The workflow waits up to 240 seconds for container health, then checks the HTTP
health endpoint. A failure marks the job failed; it does not mean Docker reverted
the application. Inspect health/events before making another change. New pushes
do not cancel a deployment already running, avoiding interruption halfway through.

After a successful update, `.deployed-image` records the deployed digest and
`.previous-image` records the previous container image, when one existed. These
files contain image names, not passwords. Before rollback, also check whether the
new application changed the database schema; restoring an image cannot undo that.

For a previous successful deployment whose image is still present locally:

```bash
cd /opt/kunballi-petclinic
PREVIOUS_IMAGE="$(cat .previous-image)"
APP_IMAGE="$PREVIOUS_IMAGE" docker compose up -d --no-build --no-deps --pull never --wait --wait-timeout 240 petclinic-api
curl -fsS http://127.0.0.1:8000/actuator/health
printf '%s\n' "$PREVIOUS_IMAGE" > .deployed-image
```

If an update failed before recording `.previous-image`, use the last successful
image recorded in `.deployed-image` instead. If an older image has been removed,
authenticate securely to GHCR and pull its recorded digest before rollback.
Never delete database volumes to recover from a bad application image.

## Limits

This remains a learning deployment. Legacy Redis 4 has no authentication and
the application's old dependencies require separate security work before public
production use. The pipeline does not add TLS, backups, database migrations,
vulnerability remediation, high availability across VMs, or production monitoring.
The application and its runner share one VM; this is not production isolation.
