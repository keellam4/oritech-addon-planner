# Oritech Addon Planner

A client-side NeoForge 1.21.1 mod that plans machine addons and extenders for [Oritech](https://github.com/Rearth/Oritech).

Pick a machine, and for multiblocks the Machine Core tier you build it with. The planner works out where to put Machine Extenders so the machine gets as many addon slots as possible. It shows the layout in 3D and can project it onto your machine as a hologram.

## Using it

- Press **P** (rebindable) to open the planner. If you're looking at an Oritech machine, the planner opens on that machine with its real core quality.
- **Extenders** cycles through the best layout for each number of extenders, so you can see what each extra extender gets you.
- **Layer** shows one layer at a time. Drag to rotate and scroll to zoom.
- **Show in world** projects the layout onto the machine you opened it from:
  - **Cyan:** extenders to place.
  - **Yellow:** free addon slots.
  - **Orange:** missing Machine Cores. Yellow means the core is a lower tier than planned.
  - **Red:** a block is in the way.
  - Blocks that are already right disappear. A status line above your hotbar counts what's left.
- There's also a key for showing or hiding the hologram. It isn't bound by default.

The mod only runs on your client. Servers don't need it, and you can join servers that don't have it.

## How the layouts are worked out

The planner copies Oritech's own addon search from `MachineAddonController`:

- The search runs `floor(core quality) + 1` steps out from the machine's addon slots. Each extender opens its six neighbours for the next step.
- With Oritech's default config (`layeredExtenders = false`), only `floor(core quality)` extenders count.
- A multiblock's quality is the average tier of its Machine Cores. Single-block machines have a fixed quality.

The planner reads each machine's addon slots and core positions from the game at startup, so machines from Oritech addons show up too.

## Building

```
./gradlew build
```

Requires Java 21.

## License

MIT
