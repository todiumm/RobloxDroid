# COMPILAR — guia passo a passo

## Pré-requisitos

- **Android Studio** compatível com AGP 8.13.2 ou SDK command-line tools
- **NDK 29.0.14206865** e **CMake 3.22.1** (via SDK Manager)
- JDK 17
- ~6 GB livres em disco (rootfs + toolchains)

## 1. Rootfs base (download verificado)

O rootfs de aproximadamente 119 MB **não fica no Git**. O Gradle executa
`prepareRootfs` antes de `preBuild`: baixa o arquivo ausente ou corrompido,
confere SHA-256 e só então publica o asset em
`app/src/main/assets/rootfs.tar.xz`. Uma cópia válida é reutilizada, inclusive
sem rede. URL e checksum estão em `rootfs.properties`.

Para preparar apenas o asset, sem SDK/NDK:

```bash
bash scripts/fetch-rootfs.sh
```

O script também verifica cópias existentes. Downloads incompletos ou com
checksum incorreto não substituem o arquivo anterior. A primeira preparação
precisa de acesso à release `rootfs-1` do PolyDroid2 no GitHub.

Use esse rootfs customizado: ele inclui as bibliotecas x86_64 necessárias.
Uma imagem Ubuntu ARM64 genérica não é equivalente. O Gradle usa `noCompress`
para evitar recomprimir o TAR/XZ no APK.

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
bash scripts/build-x86.sh # execute em Linux x86_64, não com gcc ARM64 do Termux
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
| wineboot demora na preparação | Inicialização do prefixo ou falha do Wine | A etapa usa progresso indeterminado e limite de 10 minutos; confira `rootfs/tmp/setup_wine.log` para identificar o erro |
| Studio abre preto | Driver Vulkan fraco / Mali | Configurações → Vulkan driver: alternar `system` ↔ `turnip`; ativar DXVK HUD para ver se há render |
| Studio fecha ao iniciar | D3D10/OpenGL tentado antes do D3D11 | Confirme que `ClientAppSettings.json` (FFlagDebugGraphicsPreferD3D11) está na pasta do exe |
| Crash imediato do Box64 | `STRONGMEM` insuficiente p/ o aparelho | Ative "Safe mode" nas configurações (desliga otimizações do dynarec) |
| Sem áudio | Wine não achou pulse/ALSA stub | Confira `assets/glibc-x86_64/libpulse.so.0` e logs do `AudioBridge` |
| Sem rede no Studio | DNS do convidado | Confira `rootfs/etc/hosts` e `resolv.conf` gerados no launch |

Logs úteis (`adb logcat -s RobloxDroid RobloxDroid-Vulkan Box64`) e o botão de
envio de logs nas configurações coletam session.log + logs do Studio.

## Testes de regressão

```bash
python3 scripts/test-fetch-rootfs.py
./gradlew testDebugUnitTest
```

Os testes cobrem preparação e integridade do rootfs, extração de ZIP/TAR,
hard links, links simbólicos, bloqueio de caminhos fora do destino,
substituição com rollback, localização de imports e patch do socket do Wine.
A abertura real do Studio, áudio e renderização ainda exigem teste no aparelho.
