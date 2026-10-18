#!/usr/bin/env bash
# Clean run of the whole stack. Run from your repository root:
#
#     ./DockerFile/run.sh          asks before deleting images
#     ./DockerFile/run.sh -y       no question
#
# What it does, in this order:
#   1. PRUNE   delete all unused Docker images, so no old image is reused
#   2. BUILD   build the four images one at a time (stops at the first failure)
#   3. UP      docker compose up -d   <- always the last step
#
# Before the prune it stops this project's containers; an image that a running
# container still uses cannot be deleted.
#
# WARNING: `docker image prune -af` deletes EVERY image on this machine that no
# running container uses, also images of other projects.
set -euo pipefail

[ -f docker-compose.yml ] || { echo "Run this from your repository root (docker-compose.yml not found)." >&2; exit 1; }
[ -f .env ] || { echo ".env not found in $(pwd). Create it first (see DockerFile/README.md)." >&2; exit 1; }

if [ "${1:-}" != "-y" ]; then
  read -r -p "This deletes ALL unused Docker images on this machine. Continue? [y/N] " a
  [ "$a" = y ] || [ "$a" = Y ] || { echo "Cancelled."; exit 0; }
fi

echo "== 1. PRUNE"
docker compose stop
docker image prune -af

echo "== 2. BUILD (one image at a time)"
for s in auth-service order-service executor-service frontend; do
  if docker compose config --services | grep -qx "$s"; then
    echo "-- building $s"
    docker compose build "$s" || { echo "BUILD FAILED: $s  (fix it, then run again)" >&2; exit 1; }
  else
    echo "-- $s: not in COMPOSE_PROFILES, skipped"
  fi
done
docker images | grep -E "^(frontend|auth-service|order-service|executor-service) " || true

echo "== 3. UP"
docker compose up -d                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            