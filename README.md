# RobloxDroid

**Port do Roblox Studio para Android**, construído com o mesmo mecanismo do
[PolyDroid2](https://github.com/cetotos/PolyDroid2) (port do cliente Linux do
Polytoria) — mas adaptado para a realidade do Roblox Studio, que é um programa
**Windows** e nunca teve cliente nativo Linux/Android.

> Fork derivado de PolyDroid2 (GPL-3.0) de cetotos, com todas as adaptações
> necessárias para executar o Roblox Studio via **Wine + DXVK + Box64**.

## Como funciona (a ideia do PolyDroid2, aplicada ao Studio)

```
PolyDroid 2 (referência)                RobloxDroid (este projeto)
─────────────────────────               ──────────────────────────────────
Android App (Kotlin)                    Android App (Kotlin)
  └ Termux:X11 → SurfaceView              └ Termux:X11 → SurfaceView
  └ Box64 (x86_64 → ARM64)                └ Box64 (x86_64 → ARM64)
  └ Polytoria Client (Linux x86_64)       └ Wine 9 (x86_64, Kron4ek)
                                          └ DXVK (D3D9/10/11 → Vulkan)
  └ Vulkan: Turnip/Mesa (ARM64)           └ RobloxStudioBeta.exe (Win64)
  └ Áudio: pulse stub → AudioTrack        └ Vulkan: Turnip/Mesa (ARM64)
                                          └ Áudio: pulse/ALSA stub → AudioTrack
```

Camada por camada, a cadeia de execução do RobloxDroid ganha **duas camadas a
mais** que o PolyDroid2 (Wine e DXVK) — exatamente o que separa "rodar um
cliente Linux nativo" de "rodar um programa Windows".

## Requisitos de hardware (estimados)

- Android 10+ (API 29), **arm64-v8a**
- GPU com bom suporte a Vulkan (Adreno com Turnip; Mali via driver do sistema — experimental)
- 8 GB de RAM recomendado (6 GB mínimo aceitável)
- ~4 GB de armazenamento livre (rootfs + Wine + prefixo + Studio)
- Aparelho forte: o Box64 traduz todo o código x86_64 em tempo real

## Fluxo de uso

1. Instale o APK e abra o app — o rootfs (Ubuntu 22.04 arm64) é extraído.
2. Toque em **"Instalar componentes"**:
   - Wine x86_64 (Kron4ek Wine-Builds) é baixado do GitHub;
   - DXVK x64 é baixado do GitHub;
   - o `WINEPREFIX` é inicializado (`wineboot --init` via Box64 — pode demorar minutos);
   - o **Roblox Studio** é baixado dos servidores oficiais da Roblox
     (`clientsettings.roblox.com` + `setup.rbxcdn.com`), o mesmo caminho que o
     Grapejuice usa no Linux desktop.
3. Toque em **"Abrir Roblox Studio"** — o Studio sobe dentro da tela X11 do app.
4. Faça login normalmente na janela do próprio Studio (para abrir/publish places).

### Import manual (pesquisa, versões antigas)

Copie um `RobloxStudio.zip` (sua própria cópia — ex.: build de pesquisa 2016)
para `Android/data/com.robloxdroid.studio/files/import/` e use
**Configurações → Componentes → Import** (ou o botão correspondente). Veja
`docs/LEGAL.md` para os limites legais disso.

## Estrutura do projeto

```
app/src/main/java/com/robloxdroid/studio/
  LauncherActivity.kt     # fluxo: extrair → instalar → abrir
  GameActivity.kt         # X11 + surface Vulkan + entrada de toque
  Box64Launcher.kt        # ambiente do convidado + launch.sh (Wine + Studio)
  StudioDownloader.kt     # Wine, DXVK, wineboot e Studio (CDN oficial/import)
  StudioPrefs.kt          # ClientAppSettings.json (FFlags: força D3D11→DXVK)
  RootFs.kt               # extração do rootfs + bibliotecas x86_64/ARM64
  AudioBridge.kt          # PCM via LocalSocket → AudioTrack
  LogReporter.kt          # coleta de logs (session.log, dxvk, roblox)
  com/termux/x11/…        # Termux:X11 (LorieView etc., Apache-2.0)
app/src/main/cpp/         # shims nativos (vulkan_surface_shim, X11 stubs…)
app/src/main/jniLibs/     # libbox64.so, libXlorie.so, Mesa (prebuilt ARM64)
scripts/fetch-rootfs.sh   # baixa o rootfs base em tempo de build
scripts/build-x86.sh      # recompila os shims x86_64 (requer gcc x86_64)
```

## Compilar

Veja **[docs/COMPILAR.md](docs/COMPILAR.md)**. Resumo:

```bash
./scripts/fetch-rootfs.sh        # baixa o rootfs base (~50 MB → assets)
./gradlew assembleDebug          # Android Studio ou SDK + NDK r26+
```

> ⚠️ **Pré-requisito não óbvio:** os binários `libbox64.so`, `libXlorie.so` e
> os shims `glibc-x86_64/*.so` precisam existir (já estão neste repositório,
> herdados do PolyDroid2; o `preBuild` do Gradle verifica).

## Documentação

- [docs/ARQUITETURA.md](docs/ARQUITETURA.md) — mapeamento completo PolyDroid2 → RobloxDroid, componente a componente
- [docs/COMPILAR.md](docs/COMPILAR.md) — build passo a passo e solução de problemas
- [docs/LEGAL.md](docs/LEGAL.md) — licenças (GPL-3.0, Termux:X11, Box64, Wine, DXVK, Roblox ToS)

## Status / advertências honestas

- Portado e adaptado de um projeto **beta** (PolyDroid2 0.9.3b): espere fragilidade.
- O Studio é um programa pesado de desktop: nunca foi testado oficialmente
  nesta cadeia; erros gráficos e travamentos são prováveis nas primeiras sessões.
- Performance: Box64 + Wine é o caminho mais caro possível; aparelhos
  intermediários devem esperar experiência degradada, especialmente em cenas 3D.
- Este projeto **não distribui nenhum binário da Roblox**; o Studio é obtido
  dos canais oficiais ou importado pelo usuário.
