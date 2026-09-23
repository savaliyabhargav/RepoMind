#!/usr/bin/env bash
# Installs Docker Engine + Compose v2 from the distro repositories (Debian/Ubuntu family).
# Run with: bash scripts/install-docker.sh   (it will ask for sudo)
set -euo pipefail

if ! grep -qiE 'ubuntu|debian' /etc/os-release; then
  echo "This script supports Debian/Ubuntu only. See https://docs.docker.com/engine/install/" >&2
  exit 1
fi

if command -v docker >/dev/null 2>&1; then
  echo "Docker is already installed: $(docker --version)"
else
  sudo apt-get update
  sudo apt-get install -y docker.io docker-compose-v2
fi

sudo systemctl enable --now docker

# Let the current user run docker without sudo (takes effect after re-login or `newgrp docker`)
if ! id -nG "$USER" | grep -qw docker; then
  sudo usermod -aG docker "$USER"
  echo "Added $USER to the docker group. Log out and back in (or run: newgrp docker)."
fi

echo "--- verification ---"
sudo docker --version
sudo docker compose version
sudo docker run --rm hello-world
