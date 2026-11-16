# TC4 Research Solver

A client-side Minecraft 1.7.10 mod for [GT New Horizons](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack)
that solves the Thaumcraft 4 research note in the research table with one button.

一个 GTNH（Minecraft 1.7.10）的纯客户端模组：在神秘时代 4 的研究台上加一个“一键研究”按钮，自动解出研究笔记。
[中文说明](#中文说明)见下。

## What it does

Open a research table with a research note in it and press **Solve** (left of the table screen). The mod

1. reads the note, your aspect pool, the table's bonus aspects and the ink left in the scribing tools;
2. plans the cheapest way to link every root aspect on the note, counting the aspects it has to combine first;
3. checks the whole plan against a model of the server before anything is sent, and sends nothing when aspects, ink
   or a route are missing (a chat line says which);
4. sends the same packets a player's clicks would send (aspect combinations, then placements), a few per tick;
5. waits for the server's answer and, if the note is still incomplete, plans again from the new state (twice at most).

"Cheapest" follows per-aspect weights, so common aspects are spent before rare ones. An aspect you already hold is
cheaper than one that has to be combined.

It works with the vanilla Thaumcraft research table screen and with the one from
[Thaumcraft Research Tweaks](https://github.com/GTNewHorizons/thaumcraft-research-tweaks).

## Requirements

- Minecraft 1.7.10 with Forge, as in GT New Horizons (developed and tested on GTNH 2.9.0)
- Thaumcraft 4.2.3.5
- [Forgelin](https://github.com/GTNewHorizons/Forgelin) (in GTNH already)

Client only: the server does not need the mod, and on a dedicated server it loads and does nothing. It only sends
packets Thaumcraft itself defines. Whether automating research is acceptable is up to the server you play on.

## Install

Download the jar (the one without `-dev` or `-sources`) from
[Releases](https://github.com/FYWinds/TC4ResearchSolver/releases) and put it into the client's `mods` folder.

## Configuration

`config/tc4researchsolver.cfg`, read when the game starts:

| Option | Default | Meaning |
| --- | --- | --- |
| `autoSolve` | `false` | Start solving on its own when the research table shows an unsolved note. Each note is started at most once; after a failed or stopped run, press Solve. |
| `weights` | GTNH table | `tag=weight` lines, lower is cheaper. Tags match case-insensitively; aspects not listed weigh 16. |
| `stockDiscount` | `0.5` | Cost factor for a placed aspect taken straight from your pool or the table bonus. Aspects used up in combinations cost their full weight. |
| `packetsPerTick` | `1` | Most packets sent to the server per client tick (1 is about 20 a second). |
| `syncTimeoutTicks` | `100` | Ticks to wait for the server's sync, counted from the plan's last packet, before planning again. |

## Build

```bash
./gradlew build
```

The build uses the GTNH Gradle convention plugin and needs JDK 25 for the Gradle daemon (Gradle downloads it when it
is missing). The jar is in `build/libs/`. A tag pushed to GitHub is built and published as a release by
`.github/workflows/release-tags.yml`.

## Credits

- [Thaumcraft 4](https://www.curseforge.com/minecraft/mc-mods/thaumcraft) by Azanor: the mod this is for. The solver
  follows the rules of its research table and uses its packets.
- [TC4AutoResearch](https://github.com/cdj68765/TC4AutoResearch) by cdj68765: the mod that solved research notes
  automatically before this one. This is a separate rewrite that runs inside the game on any platform and keeps the
  GTNH research table screen.
- [Thaumcraft Research Tweaks](https://github.com/GTNewHorizons/thaumcraft-research-tweaks) by Telan and Think (with
  TimeConqueror and the GTNH developers): the research table screen GTNH players use; the button is made to work in it.
- [TC4Tweaks](https://github.com/Glease/TC4Tweaks) by Glease: its server-side check of aspect combinations is part of
  the server model the plan is checked against.
- The GTNH Chinese wiki ([gtnh.huijiwiki.com](https://gtnh.huijiwiki.com/wiki/%E7%A5%9E%E7%A7%98%E6%97%B6%E4%BB%A3))
  and its 神秘时代研究助手 tool: the idea of weighting aspects by how hard they are to get, and the default weights,
  come from its table.
- [GT New Horizons](https://github.com/GTNewHorizons): the modpack, the
  [build convention](https://github.com/GTNewHorizons/GTNHGradle) and
  [ExampleMod](https://github.com/GTNewHorizons/ExampleMod1.7.10) this project is set up from, Forgelin, and the
  release workflows.

No code of these projects is included.

## License

[GPL-3.0](LICENSE). Copyright (C) 2026 FYWinds.

## 中文说明

打开放有研究笔记的研究台，点界面左边的 **一键研究**。模组会：

1. 读取笔记、你的要素库存、研究台的赠送要素和墨水余量；
2. 按要素权重算出把笔记上所有固定要素连起来的最省方案，缺的复合要素先合成；
3. 发送前用一个服务端模型把整个方案核对一遍，要素、墨水不够或连不通时什么都不发，并在聊天栏说明原因；
4. 发送的是和手动点击完全一样的数据包（先合成，后放置），每 tick 限量；
5. 等服务端同步后如果笔记还没完成，按新状态重新规划，最多两次。

纯客户端，服务端不用装。需要神秘时代 4.2.3.5 和 Forgelin（GTNH 自带），原版研究台界面和 Thaumcraft Research Tweaks
的界面都能用。在 GTNH 2.9.0 上开发和测试。能不能在服务器上用自动研究，由你所在的服务器决定。

配置文件 `config/tc4researchsolver.cfg`（启动时读取）：`autoSolve` 研究台里出现未完成的笔记时自动开始（默认关）；
`weights` 要素权重，越小越优先使用，默认值取自 GTNH 中文维基的神秘时代研究助手；`stockDiscount` 直接从库存里拿来放置的要素的
折扣；`packetsPerTick` 每 tick 最多发几个包；`syncTimeoutTicks` 等服务端同步的 tick 数。
