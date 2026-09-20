# ARQUITETURA — do PolyDroid2 ao RobloxDroid

Este documento registra, componente a componente, como o mecanismo do
PolyDroid2 (que executa o cliente **Linux** do Polytoria no Android) foi
adaptado para executar o **Roblox Studio** (programa **Windows**) no Android.
É o mesmo princípio do PolyDroid2 — tradução de x86_64 para ARM64 e uma ponte
de Vulkan para a Surface do Android — com as camadas extras que o Studio exige.

## 1. A diferença fundamental

O Polytoria distribui um cliente Linux x86_64 (Unity 1.0 / Godot 2.0). O
PolyDroid2 portanto só precisa de **uma** camada de tradução:

```
PolyDroid2:  Box64 → Polytoria Client.x86_64 (ELF Linux)
```

O Roblox Studio **nunca teve cliente Linux**. O caminho "exatamente como o
PolyDroid2" exigiria um binário Linux inexistente; a solução é executar o
binário Windows oficial sob Wine — o mesmo padrão do Winlator (que o PolyDroid2
cita como referência) e do Mobox:

```
RobloxDroid:  Box64 → Wine (ELF Linux x86_64) → RobloxStudioBeta.exe (PE Win64)
                                                       └ DXVK: D3D11 → Vulkan
```

Duas camadas adicionais (Wine, DXVK), mesmo modelo de orquestração.

## 2. Mapeamento de componentes

| Componente | PolyDroid2 | RobloxDroid | Observações |
|---|---|---|---|
| Emulador CPU | `libbox64.so` (ARM64, em jniLibs) | idem | Reaproveitado sem mudanças |
| Display | Termux:X11 (`libXlorie.so` + LorieView) | idem | Reaproveitado |
| Ponte Vulkan→Surface | `vulkan_surface_shim.so` (`cpp/vulkan.c`) + `libxvk_droid.so` | idem | O shim intercepta o WSI X11 e apresenta na Surface Android (`ROBLOXDROID_VULKAN_SURFACE_PTR`) |
| Driver Vulkan | Turnip (`libvulkan_freedreno.so`) ou sistema | idem | Mesmo ICD JSON gerado em RootFs |
| Camada Windows | — (cliente nativo) | **Wine x86_64 (Kron4ek 9.x)** | Baixado na 1ª execução, extraído em `/opt/wine` do rootfs |
| Tradução D3D | — (Vulkan nativo/OpenGL) | **DXVK x64** | DLLs copiadas ao `system32` do prefixo + `WINEDLLOVERRIDES=d3d11,d3d10core,d3d9,dxgi=n,b` |
| Obtenção do "cliente" | CDN do Polytoria (`ClientDownloader`) | CDN oficial da Roblox (`StudioDownloader.installOfficialStudio`) ou import do usuário | Mesma origem usada pelo Grapejuice no Linux |
| Ajustes de engine | `PolytoriaPrefs` (Unity prefs XML) / `Polytoria2Prefs` (Godot JSON) | `StudioPrefs` (`ClientAppSettings.json` + FFlags) | `FFlagDebugGraphicsPreferD3D11=true` força o caminho DXVK |
| Áudio | `libpulse.so.0` stub → `AudioBridge` (LocalSocket → AudioTrack) | idem + `libasound.so.2` stub (ALSA do Wine) | Wine usa driver pulse ou ALSA → stub → bridge |
| Login | Chrome Custom Tabs (polytoria.com) → deeplink | **Removido** — login acontece na janela do Studio | Launcher tem botões Instalar/Abrir |
| DNS/hosts | hosts pré-resolvidos p/ api.polytoria.com | hosts pré-resolvidos p/ domínios roblox.com/rbxcdn | Mesma técnica (Box64 não usa nss do Android) |
| Shims x86_64 | `BOX64_LD_PRELOAD`: eaccess, pthread_recursive_fix, ctype (Godot), unity_crash_fix, dns_resolver, connect_redirect | Versão enxuta: eaccess, pthread_recursive_fix, ctype_fix, dns_resolver, connect_redirect | Removidos os específicos de Unity/Godot (sem efeito no Wine) |
| Rootfs | Ubuntu Jammy ARM64 **com cliente embutido** | Ubuntu 22.04 ARM64 **limpo** (cloud-image) | Wine/DXVK/Studio chegam na 1ª execução |
| Tuning Box64 | Perfil Godot/Unity (`DYNAREC_BIGBLOCK=2`, etc.) | Perfil estilo Winlator (`BIGBLOCK=1`, `STRONGMEM=1`, `MMAP32=1`) | Wine é sensível a STRONGMEM/MMAP32 |
| Logs | session.log + Player.log + godot logs | session.log + `%LOCALAPPDATA%/Roblox/logs` + dxvk-logs | LogReporter adaptado |

## 3. Fluxo de execução (abrir Studio)

1. `LauncherActivity` → extrai rootfs (1ª vez) e valida componentes.
2. `GameActivity` inicia o X server (`CmdEntryPoint.start(":0")`), conecta o
   `LorieView`, inicia `AudioBridge` e passa o ponteiro da Surface para o
   arquivo `vulkan_surface_ptr` (lido pelo shim nativo).
3. `Box64Launcher.launch(...)`:
   - valida Wine, WINEPREFIX e `RobloxStudioBeta.exe`
     (`%LOCALAPPDATA%/Roblox/Versions/version-*` ou `/opt/roblox`);
   - `StudioPrefs.applyTo` grava `ClientAppSettings.json` ao lado do exe;
   - monta o ambiente (`guestEnv`): `WINEPREFIX`, `WINEDLLOVERRIDES` (DXVK),
     `VK_ICD_FILENAMES` (Turnip ARM64), `BOX64_*` (perfil Wine), X11, DNS;
   - gera `tmp/launch.sh` e executa via `/system/bin/sh`:
     `taskset <big cores> libbox64.so /opt/wine/bin/wine "C:\...\RobloxStudioBeta.exe"`.
4. O Wine emula Win64 (todo o código x86_64 traduzido pelo Box64), o Studio
   inicializa D3D11 → DXVK → Vulkan (Turnip) → ponte de surface → tela.
5. Teclado/mouse do X11 (com `TouchInputHandler`) operam a UI ribbon do Studio;
   `CustomKeysOverlay` provê teclas virtuais configuráveis.

## 4. Versão 32 bits (build de pesquisa 2016)

O binário compilado do vazamento de 2016 é **Win32 (x86)**. Executá-lo exige
`box86` + `wine32` (fora do escopo atual, que é Win64). Recomendações:

- Prefira um Studio x64 moderno (CDN oficial) — caminho suportado.
- Se for experimentar o binário 32 bits, importe-o e providencie uma build
  `box86` + wine-i386; o ambiente (`BOX64_MMAP32=1`) já está preparado, mas o
  `libbox86.so` não acompanha este repositório.

## 5. O que permaneceu intocado

Toda a infraestrutura de baixo nível do PolyDroid2 foi reaproveitada sem
modificação funcional — é o que torna este port possível:

- `cpp/vulkan.c` (2.374 linhas) — a ponte Vulkan/X11→Surface, "most of the
  project", nas palavras do autor original;
- Termux:X11 completo (LorieView, input, Samsung DeX);
- libbox64 pré-compilada, Mesa/Turnip, Xvfb bundle;
- overlays de toque, editor de overlay, stats de sistema, proteção térmica.
