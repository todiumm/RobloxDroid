# LEGAL — licenças e limites de uso

## 1. Código deste repositório (RobloxDroid)

Fork derivado de **PolyDroid2** (© cetotos), licenciado sob **GNU GPL-3.0**.
Este projeto mantém a mesma licença (arquivo `LICENSE`), e o arquivo `NOTICE`
credita os projetos de origem. Se você distribuir APKs deste fork, eles também
devem ser GPL-3.0 com código-fonte disponível.

## 2. Componentes reaproveitados e suas licenças

| Componente | Origem | Licença |
|---|---|---|
| PolyDroid2 (base do fork) | github.com/cetotos/PolyDroid2 | GPL-3.0 |
| Termux:X11 (`com/termux/x11/*`, `libXlorie.so`) | github.com/termux/termux-x11 | GPL-3.0 (Termux) / Apache-2.0 em partes — ver NOTICE |
| Box64 (`libbox64.so`) | github.com/ptitSeb/box64 | BSD-3-Clause |
| Mesa / Turnip (`libvulkan_freedreno.so` etc.) | mesa3d.org | MIT (Mesa) |
| Wine (baixado em runtime) | Kron4ek Wine-Builds / winehq.org | LGPL-2.1 |
| DXVK (baixado em runtime) | github.com/doitsujin/dxvk | zlib/libpng |
| Ubuntu rootfs (build-time) | cloud-images.ubuntu.com | Ubuntu / CFLA |
| Winlator (referência de arquitetura) | github.com/brunodev85/winlator | GPL-3.0 (referência apenas) |

## 3. Roblox Studio — o mais importante

- O Roblox Studio é software **proprietário da Roblox Corporation**. Este
  projeto **não contém, não empacota e não distribui** nenhum binário da Roblox.
- O Studio é obtido por download direto dos **canais oficiais** da Roblox
  (`clientsettings.roblox.com`, `setup.rbxcdn.com`) — a mesma origem usada pelo
  launcher oficial no Windows e pelo projeto Grapejuice no Linux — ou importado
  pelo próprio usuário.
- **É responsabilidade do usuário** garantir que sua utilização do Studio
  (inclusive em ambiente não suportado, como Android via Wine) está em
  conformidade com os **Termos de Uso da Roblox** e o aviso de ferramentas
  não oficiais. A Roblox pode atualizar o Studio a qualquer momento de forma
  que o quebre sob Wine/Box64.
- Para a **build de pesquisa (vazamento de 2016)**: esse código/binários são
  propriedade da Roblox Corp. Uso **exclusivamente privado, para pesquisa
  pessoal e engenharia reversa educacional**, sem distribuição de binários,
  APKs ou builds derivados. A distribuição de builds compiladas desse material
  é ilegal e viola direitos autorais.

## 4. Marcas registradas

"Roblox" e "Roblox Studio" são marcas registradas da Roblox Corporation.
Este projeto não é afiliado, patrocinado ou endossado pela Roblox Corporation,
assim como o PolyDroid2 não é afiliado ao Polytoria.

## 5. Sem garantias

Programa distribuído "como está", sem garantia de qualquer tipo (GPL-3.0 §15).
O uso é experimental por natureza: travamentos, perda de dados de places em
edição e sobreaquecimento do aparelho são possíveis.
