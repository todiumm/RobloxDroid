#!/usr/bin/env bash
# RobloxDroid — baixa e prepara o rootfs base para embutir em
# app/src/main/assets/rootfs.tar.xz.
#
# IMPORTANTE: usamos o MESMO rootfs customizado do PolyDroid2 (release
# "rootfs-1" do autor original, SHA-256 verificado). Ele contém o stack
# x86_64 multiarch (freetype, X11 client, udev, gcrypt, zlib/brotli…)
# que o Wine precisa — o Ubuntu arm64 vanilla NÃO tem nada disso e fazia
# o wineboot falhar / o Studio ficar sem fontes.
#
# Se assets/rootfs.tar.xz já existir, nada é baixado — o zip do projeto
# já vem com o rootfs embutido, então este script é opcional.
#
# Uso: ./scripts/fetch-rootfs.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
mkdir -p "$ASSETS"

ROOTFS_URL="https://github.com/cetotos/PolyDroid2/releases/download/rootfs-1/rootfs.tar.xz"
ROOTFS_SHA="2a61930a4c2a8efe780a935f84df947640407594b8f9ec8bba960afb5bcd7d34"
ROOTFS_OUT="$ASSETS/rootfs.tar.xz"

if [ -f "$ROOTFS_OUT" ]; then
  echo "rootfs.tar.xz já existe em assets — nada a fazer."
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Baixando rootfs customizado (PolyDroid2 rootfs-1)…"
curl --fail --location --retry 3 --retry-delay 5 \
  --progress-bar -o "$TMP/rootfs.tar.xz" "$ROOTFS_URL"

echo "Verificando SHA-256…"
echo "$ROOTFS_SHA  $TMP/rootfs.tar.xz" | sha256sum -c -

mv "$TMP/rootfs.tar.xz" "$ROOTFS_OUT"
echo "OK: $(du -h "$ROOTFS_OUT" | cut -f1) em $ROOTFS_OUT"
echo "Lembre: Wine/DXVK/Studio são baixados pelo app na primeira execução."
