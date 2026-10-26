# PetClinic CI/CD with Docker Hub and an AWS VM runner

## What this prepares

```text
Push to main -> GitHub-hosted runner builds and tests -> Docker Hub stores image
             -> your AWS Ubuntu VM runner pulls that exact image -> Compose starts PetClinic
```

The image is named `docker.io/<dockerhub-username>/kunballi-petclinic`. Each successful
build publishes `sha-<full-commit-id>` and `latest` tags. Deployment pulls the
commit tag instead of the moving `latest` tag. Tags can be overwritten in Docker
Hub, so do not manually replace a published commit tag with a different image.
The existing Dockerfile runs the Maven tests before creating the final image.
Pull requests run build/tests only, on a GitHub-hosted runner, without publishing
an image or running deployment on your VM.

No VM is created or registered by these files. Deployment is disabled until
you explicitly set `PETCLINIC_DEPLOY_ENABLED` to `true` in GitHub.
The GitHub repository is `TECH-TENA/petclinic-docker`. Commit and push these
workflow changes to `main` when the configuration below is ready.

## 1. Connect to your AWS Ubuntu VM

Use an existing, dedicated EC2 instance. These commands assume its operating
system is Ubuntu and its architecture is x64. They are not Amazon Linux commands.
On your laptop, open Git Bash and connect using your actual private key filename
and the instance's current public address. The key does not have to end in `.pem`:

```bash
ssh -i "/c/path/to/YOUR_PRIVATE_KEY" ubuntu@YOUR_EC2_PUBLIC_IP
```

After logging in, run these on the VM:

```bash
cat /etc/os-release
uname -m
whoami
docker --version
docker compose version
```

Expect Ubuntu, `x86_64`, and a non-root username such as `ubuntu`. If the OS is
Amazon Linux, or the architecture is `aarch64`, stop and adapt the setup before
continuing. The current workflow builds Linux amd64 images and targets an x64 runner.

Your EC2 security group should allow SSH from your own IP, not the entire internet.
The runner connects outward to GitHub over HTTPS; it does not need a new inbound
"GitHub runner port". Keep outbound connectivity for GitHub, Docker Hub, and package
downloads. Never open MySQL, Redis, or Sentinel ports to the internet.

### Docker prerequisites

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

If Docker is already installed, do not replace it blindly. Check its version
and Compose plugin first. For a fresh Ubuntu VM with no conflicting Docker
packages, follow the linked Docker installation guide, then install Git/curl:

```bash
sudo apt update
sudo apt install -y git curl
```

For a dedicated non-root runner user that you trust with root-level Docker access:

```bash
sudo usermod -aG docker "$(id -un)"
exit
```

Reconnect by SSH so the new group membership takes effect, then check:

```bash
docker info >/dev/null
docker compose version
```

Both must work without sudo before installing the runner service. If a runner
service was installed before granting Docker access, restart that service after
updating group membership. Do not use `chmod 666 /var/run/docker.sock`.

## 2. Use your existing runner

Use the runner you registered with the name `kunballi`. Idle means it is online
and waiting for a job; you do not need to register another one.
Open Settings -> Actions -> Runners -> kunballi -> Labels, and add the custom
label `kunballi`. Keep its default `self-hosted`, `Linux`, and `X64` labels.

The runner name and label are separate. A runner called `kunballi` is not
automatically given a `kunballi` label. The deployment job requires all four
labels in `runs-on: [self-hosted, linux, x64, kunballi]` to select your VM.
See GitHub's [runner label instructions](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/apply-labels).

Instead of leaving `./run.sh` in a foreground SSH session, install it as a
service so it continues working after you disconnect. If `./run.sh` is already
running, stop it with Ctrl+C first. In the runner directory on the VM:

```bash
cd ~/actions-runner
sudo ./svc.sh install "$(id -un)"
sudo ./svc.sh start
sudo ./svc.sh status
```

Return to Settings -> Actions -> Runners on GitHub. Confirm the runner shows
Idle and has all four required labels. Idle means it is online and waiting
for work; it is not an error. The EC2 instance and runner service must stay
running to pick up deployment jobs. Keep the runner software separate from
the application folder.
Never put its registration token, `.runner`, or `.credentials` files in this
project or share them in chat.

To undo runner registration later, stop and uninstall this specific service
from its own runner directory, then use GitHub's removal instructions. Do not
delete the application folder or database volumes while removing a runner.

## 3. Prepare the application folder once

Keep the deployed application outside `actions-runner/_work`, which GitHub may
clean between jobs. Use `~/petclinic-docker`, beside `~/actions-runner`, not inside
it. These commands assume the logged-in user is the same non-root user that runs
the runner service. For the Ubuntu user, the application path is
`/home/ubuntu/petclinic-docker`.

### Move the existing checkout before the first deployment

If you already cloned into `/opt/kunballi-petclinic`, do not delete or clone it
again. Before starting the application for the first time, move the existing
folder on the VM. This preserves its `.env`, `.git`, and configuration files:

```bash
sudo mv -nT /opt/kunballi-petclinic "$HOME/petclinic-docker"
cd "$HOME/petclinic-docker"
pwd
ls -la
```

The `-n` option prevents overwriting an existing destination, and `-T` prevents
nesting the old folder inside another folder. If the destination already exists,
stop and inspect both folders instead of deleting either one. This move does not
delete Docker volumes. If PetClinic containers have already been created, plan
the migration first: changing the folder can also change the Compose project
name. Do not move a running deployment using these first-setup instructions.

### Clone only if there is no existing checkout

Use your normal secure Git authentication; never embed a token in the URL.
Only run this clone and copy sequence when preparing a new deployment folder.

```bash
cd ~
git clone https://github.com/TECH-TENA/petclinic-docker.git "$HOME/petclinic-docker"
cd "$HOME/petclinic-docker"
cp -n .env.example .env
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

Run these only from `~/petclinic-docker` on the VM, not on the laptop or
someone else's deployment. Do not make everything world-writable.
See README-local.md for what each configuration does.

This creates a fresh VM database; it does not migrate records from your laptop.
If the VM already has PetClinic data, identify its existing volume and credentials
instead of creating/repointing storage blindly. Do not use `down -v` or delete volumes.

## 4. Enable deployment in GitHub

### Docker Hub repository and credentials

In Docker Hub, create a repository named `kunballi-petclinic` under your own
Docker Hub username. The username need not match your GitHub username.
Create an expiring personal access token with Read/Write permissions for the
build job, and a separate Read-only token for deployment. Do not grant Delete
permission when it is not needed. See the official [Docker access-token guide](https://docs.docker.com/security/access-tokens/).

Under GitHub Settings -> Secrets and variables -> Actions -> Secrets, add:

| Name | Value | Purpose |
| --- | --- | --- |
| `DOCKERHUB_TOKEN` | Your Read/Write Docker Hub token | The hosted build runner uploads the image. |
| `DOCKERHUB_READ_TOKEN` | Your separate Read-only Docker Hub token | The VM downloads images without registry-write access. |

Never put token values in workflow YAML, commit them, or send them in chat.
The GitHub runner registration token is separate from both Docker Hub tokens.

No GitHub environment needs to be created for this basic workflow. Protect
`main` and review changes before merging. Deployment runs after a successful
main-branch build, only when the enable variable below is `true`.

Under Settings -> Secrets and variables -> Actions -> Variables, add:

| Name | Value | Purpose |
| --- | --- | --- |
| `DOCKERHUB_USERNAME` | Your actual lowercase Docker Hub username | The image namespace and Docker Hub login name; this is not a secret. |
| `PETCLINIC_DEPLOY_ENABLED` | `true` | Enable the VM deploy job only after setup is complete. |

The self-hosted runner executes commands directly on the VM, so no SSH key
secret or AWS access keys are needed by this deployment workflow. Docker Hub
authentication uses the two tokens above, not `GITHUB_TOKEN`. Keep the username
in Variables and the tokens in Secrets. The Docker login action logs out after
the job finishes. The VM uses a temporary Docker authentication directory rather
than replacing the runner user's usual Docker login.

The deployment folder is `~/petclinic-docker` under the runner service user's
home directory. The workflow uses `cd "$HOME/petclinic-docker"` for both checks
and deployment. Commit and push this workflow change before testing the new path.
The older `PETCLINIC_DEPLOY_DIR` variable and `staging` environment are no longer
used by this workflow; do not add them as prerequisites.

## 5. Trigger and check the pipeline

Push a reviewed commit to `main`, or open Actions -> Kunballi PetClinic CI/CD ->
Run workflow and choose `main`. Check the build job first, then the deploy job.
If deployment is skipped, check `PETCLINIC_DEPLOY_ENABLED`. If it stays queued,
check that the VM runner is online and has all four required labels.

On the VM, inspect the running stack without printing secrets:

```bash
cd "$HOME/petclinic-docker"
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

After a successful update, `.deployed-image` records the deployed image tag and
`.previous-image` records the previous container image, when one existed. These
files contain image names, not passwords. Before rollback, also check whether the
new application changed the database schema; restoring an image cannot undo that.

For a previous successful deployment whose image is still present locally:

```bash
cd "$HOME/petclinic-docker"
PREVIOUS_IMAGE="$(cat .previous-image)"
APP_IMAGE="$PREVIOUS_IMAGE" docker compose up -d --no-build --no-deps --pull never --wait --wait-timeout 240 petclinic-api
curl -fsS http://127.0.0.1:8000/actuator/health
printf '%s\n' "$PREVIOUS_IMAGE" > .deployed-image
```

If an update failed before recording `.previous-image`, use the last successful
image recorded in `.deployed-image` instead. If an older image has been removed,
authenticate securely to Docker Hub and pull its recorded image before rollback.
Never delete database volumes to recover from a bad application image.

## Limits

This remains a learning deployment. Legacy Redis 4 has no authentication and
the application's old dependencies require separate security work before public
production use. The pipeline does not add TLS, backups, database migrations,
vulnerability remediation, high availability across VMs, or production monitoring.
The application and its runner share one VM; this is not production isolation.
