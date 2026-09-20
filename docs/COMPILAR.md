# COMPILAR — guia passo a passo

## Pré-requisitos

- **Android Studio** (Hedgehog ou mais recente) ou SDK command-line tools
- **NDK r26+** e **CMake 3.22+** (via SDK Manager)
- JDK 17
- ~6 GB livres em disco (rootfs + toolchains)

## 1. Rootfs base (já incluído no zip)

O zip do projeto **já vem com `app/src/main/assets/rootfs.tar.xz`** — o MESMO
rootfs customizado do PolyDroid2 (release `rootfs-1`, SHA-256 verificado no
script). Ele contém o stack x86_64 multiarch (freetype, X11 client, udev,
gcrypt, zlib/brotli…) que o Wine e o Studio precisam.

Só rode o script abaixo se você apagou o arquivo do assets:

```bash
./scripts/fetch-rootfs.sh
```

> Importante: NÃO troque pelo Ubuntu arm64 vanilla (cloud-image). Além de não
> ter as libs x86_64 (o Wine fica sem freetype/X11 — Studio sem fontes e
> wineboot quebrado), a versão v9 do `RootFs` espera o layout do rootfs
> oficial.

> Nota sobre APKs: o `rootfs.tar.xz` (~119 MB) é o maior asset do APK; o
> `noCompress` no `build.gradle.kts` garante que ele não seja recomprimido.

## 2. Compilar

```bash
# linha de comando
./gradlew assembleDebug
# APK em app/build/outputs/apk/debug/app-debug.apk

# ou: abra o diretório no Android Studio e rode ▶
```

O Gradle valida os binários pré-construídos exigidos (`preBuild`):

- `app/src/main/jniLibs/arm64-v8a/libbox64.so` (Box64)
- `app/src/main/jniLibs/arm64-v8a/libXlorie.so` (Termux:X11)
- `app/src/main/assets/glibc-x86_64/*` (glibc x86_64 + stubs pulse/dbus)
- `app/src/main/assets/x86_64-libs/*` (shims: eaccess, ctype, dns…)

Todos já estão neste repositório (herdados do PolyDroid2). Se você alterar os
shims em `app/src/main/cpp/`, recompile-os num Linux x86_64:

```bash
./scripts/build-x86.sh     # requer gcc-multilib (destino i386/amd64)
```

## 3. Instalar no aparelho

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Primeira execução dentro do app: extração do rootfs (~1-3 min), depois o botão
**"Instalar componentes"** (Wine ~90 MB, DXVK ~40 MB, wineboot 2-10 min em
dynarec, Studio ~350 MB). Total: prepare-se para 15-40 min na primeira vez,
dependendo do aparelho.

## 4. Solução de problemas

| Sintoma | Causa provável | O quê fazer |
|---|---|---|
| `Missing pre-built: …` no Gradle | Binários pré-construídos ausentes | Confirme que `jniLibs` e `assets/glibc-x86_64` não foram limpos |
| **wineboot sai com código 255** | Executável não-ELF passado ao Box64: `bin/wineboot` do Kron4ek é script `#!/bin/sh` e `bin/wine` é ELF i386 (32 bits) — Box64 só roda x86_64 | Atualize para esta versão: o app agora roda `box64 wine64 wineboot --init` (validação ELF embutida). Se persistir, confira no log qual binário o app tentou executar |
| wineboot trava em 0% | Sem X server no primeiro boot | Abra pelo botão do launcher (o X11 sobe no GameActivity); se rodar pelo Settings, aguarde — wineboot funciona headless com avisos |
| Studio abre preto | Driver Vulkan fraco / Mali | Configurações → Vulkan driver: alternar `system` ↔ `turnip`; ativar DXVK HUD para ver se há render |
| Studio fecha ao iniciar | D3D10/OpenGL tentado antes do D3D11 | Confirme que `ClientAppSettings.json` (FFlagDebugGraphicsPreferD3D11) está na pasta do exe |
| Crash imediato do Box64 | `STRONGMEM` insuficiente p/ o aparelho | Ative "Safe mode" nas configurações (desliga otimizações do dynarec) |
| Sem áudio | Wine não achou pulse/ALSA stub | Confira `assets/glibc-x86_64/libpulse.so.0` e logs do `AudioBridge` |
| Sem rede no Studio | DNS do convidado | Confira `rootfs/etc/hosts` e `resolv.conf` gerados no launch |

Logs úteis (`adb logcat -s RobloxDroid RobloxDroid-Vulkan Box64`) e o botão de
envio de logs nas configurações coletam session.log + logs do Studio.
