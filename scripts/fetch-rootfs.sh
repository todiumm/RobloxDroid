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
# O arquivo não é versionado. Downloads e cópias existentes são verificados.
#
# Uso: ./scripts/fetch-rootfs.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
mkdir -p "$ASSETS"

ROOTFS_URL="$(sed -n 's/^url=//p' "$ROOT/rootfs.properties")"
ROOTFS_SHA="$(sed -n 's/^sha256=//p' "$ROOT/rootfs.properties")"
ROOTFS_OUT="$ASSETS/rootfs.tar.xz"

[[ "$ROOTFS_URL" == https://* && "$ROOTFS_SHA" =~ ^[a-f0-9]{64}$ ]] || {
  echo "rootfs.properties inválido" >&2
  exit 1
}
verify() {
  echo "$ROOTFS_SHA  $1" | sha256sum -c - >/dev/null 2>&1
}

if [ -f "$ROOTFS_OUT" ] && verify "$ROOTFS_OUT"; then
  echo "rootfs.tar.xz já existe e o SHA-256 está correto."
  exit 0
fi

TMP="$(mktemp -d "$ASSETS/.rootfs-download.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

echo "Baixando rootfs customizado (PolyDroid2 rootfs-1)…"
curl --fail --location --connect-timeout 30 --retry 3 --retry-delay 5 \
  --progress-bar -o "$TMP/rootfs.tar.xz" "$ROOTFS_URL"

echo "Verificando SHA-256…"
verify "$TMP/rootfs.tar.xz" || { echo "SHA-256 do rootfs incorreto" >&2; exit 1; }

mv "$TMP/rootfs.tar.xz" "$ROOTFS_OUT"
echo "OK: $(du -h "$ROOTFS_OUT" | cut -f1) em $ROOTFS_OUT"
echo "Lembre: Wine/DXVK/Studio são baixados pelo app na primeira execução."
