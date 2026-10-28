# svd

Turns a CMSIS **SVD** description of a chip into sysl.

A vendor ships an SVD saying where every peripheral lives, which registers it has, and which bits of
each register mean what. This reads one and writes sysl `const` declarations — one module per
peripheral — so that a driver can say `io_bank0.GPIO0_CTRL_OFFSET` instead of a hex literal somebody
copied out of a datasheet.

It is a build tool. It runs on a developer's machine, never on a target, and nothing it produces
depends on it at run time.

```
sbt 'run survey RP2350.svd'
sbt 'run constants RP2350.svd -o lib -m sh.sysl.rp2350'
```

## What it emits

One module per peripheral, at the module's own name twice, which is how a sub-module is laid out
everywhere in this org:

```
lib/sh/sysl/rp2350/timer0/timer0.sysl
```

```sysl
// Controls time and alarms time is a 64 bit value indicating the time since power-on ...

module sh.sysl.rp2350.timer0

// The peripheral's base address. Every offset below is from here.
const BASE: u32 = 0x400b0000

// Arm alarm 0, and configure the time it will fire.
const ALARM0_OFFSET: u32 = 0x00000010
const ALARM0_ALARM0_MASK: u32 = 0xffffffff
const ALARM0_ALARM0_SHIFT: u32 = 0
```

The datasheet's own spelling is kept for every name *inside* a module, so that reading the datasheet
and grepping the source are the same activity. Only the module name is lower-cased.

The integer type comes from the device's own `<width>`, so a 32-bit chip gets `u32`.

## Options

| | |
|---|---|
| `-o, --output` | the library root to write modules under |
| `-m, --module-root` | the module each peripheral becomes a sub-module of; defaults to `sh.sysl.rp2350` |
| `--descriptions` | `none`, `registers` (the default), or `all` — how much of the SVD's prose becomes comments |
| `--absolute` | emit each register's absolute address as well as its offset |
| `--derived` | `expand` (the default) or `base-only` — see below |
| `--include`, `--exclude` | which peripherals to emit, by SVD name |
| `--patch` | a file of corrections to apply to the SVD first — see below |
| `--dry-run` | report what would be written, and write nothing |

`svd survey <file>` reports what is in an SVD without emitting anything, which is the quickest way to
find out what a filter should say.

## What it refuses

**Anything it does not understand stops the run.** SVD carries a good deal this tool does not model —
`<dim>` arrays, clusters, alternate register groups, write constraints — and the tempting alternative
is to ignore what it was not written for. That is much worse than it sounds: a dropped `<dim>` array
is thirty-one registers that do not exist as far as every program compiled against the output is
concerned, the generated file still compiles, and the failure surfaces days later as hardware not
responding. A register map is exactly the kind of artifact nobody proofreads.

So each unsupported element is refused by name, at the register or field that used it:

```
error: register 'DMA.CH0_CTRL' uses <dim> (a register or field array), which this tool does not
understand — it would be dropped silently, so the run stops instead
```

Adding support for one of them is a small job. Discovering you needed it is not.

A derived peripheral that also declares registers of its own is refused for the same reason: taking
its parent's registers would drop them.

**An alternate register is not refused**, because noting it loses nothing. ST's timers declare
`CCMR1_Output` and `CCMR1_Input` at one offset, the input reading of the word naming the output one
with `<alternateRegister>`. Each is a complete register, so both are emitted with their own fields, and
the one naming the other carries a comment saying so.

## Correcting a vendor's SVD

Vendors' SVDs are wrong in places — a bit left out, a field two bits too narrow, a second core's block
of registers missing — and the wrong value is the one every program compiled against the generated map
believes. Editing the generated files is undone by the next regeneration; editing the vendor's XML
hides the change in a file nobody diffs. So a correction goes in a **patch file**, in HOCON, applied
to the parsed SVD before anything is emitted:

```
svd constants STM32H747_CM7.svd --patch patches/stm32h747.patch.hocon -o . -m sh.sysl.stm32h747
```

```hocon
device = STM32H747_CM7      // optional; a patch for another device is refused
patches = [
  {
    op = modify-field, peripheral = LTDC, register = SSCR, field = HSW
    set { bitWidth = 12 }
    why = "RM0399 LTDC_SSCR: HSW[11:0] is bits 27:16; stm32h747xx.h agrees (0x0FFF0000)"
  }
  {
    op = add-field, peripheral = RCC, register = APB3ENR
    field { name = DSIEN, bitOffset = 4, bitWidth = 1, access = read-write, description = "DSI clock enable" }
    why = "RM0399 RCC_APB3ENR bit 4"
  }
]
```

Every entry has an `op` and a **`why`**, which is required: a correction says what it was checked
against. The operations follow the set the Rust embedded ecosystem's svdtools settled on:

| `op` | takes |
|---|---|
| `add-peripheral` | `peripheral { name, baseAddress, description?, derivedFrom? }` |
| `modify-peripheral` | `peripheral`, `set { name?, baseAddress?, description? }` — a rename carries every `derivedFrom` with it |
| `delete-peripheral` | `peripheral` |
| `add-register` | `peripheral`, `register { name, addressOffset, description?, size?, access?, resetValue?, alternateOf?, fields = [...] }` |
| `modify-register` | `peripheral`, `register`, `set { name?, addressOffset?, description?, size?, access?, resetValue? }` |
| `delete-register` | `peripheral`, `register` |
| `add-field` | `peripheral`, `register`, `field { name, bitOffset, bitWidth, description?, access? }` |
| `modify-field` | `peripheral`, `register`, `field`, `set { name?, bitOffset?, bitWidth?, description?, access? }` |
| `delete-field` | `peripheral`, `register`, `field` |
| `copy-registers` | `peripheral`, `registers = [...]`, `offset`, `prefix { from, to }` — each register again `offset` bytes on, renamed by prefix (svdtools' `_derive` of a block: a dual-core chip's second per-core block) |
| `rename-registers` | `peripheral`, `registers = [...]`, `prefix { from, to }` |

A number is a HOCON number or a string in SVD's own syntax, which is how hex is written:
`addressOffset = "0xC0"`. Entries apply in order. A register-level change to a derived peripheral is
refused and pointed at its parent, which it then follows.

**A patch that does not apply stops the run.** An entry naming something the SVD does not have, one
that changes nothing, a key an operation does not take (a misspelt `bitwidth`), a field that would
overlap another or run off its register, a register landing on an offset another holds — each is an
error naming the file and the entry. A stale patch is almost always one written against a different
SVD, or one the vendor has since fixed, and it must not rot in silence.

Every module a patch changed says so in its header, naming the file, its SHA-256, and each `why` that
applied to it; a derived peripheral written out in full carries its parent's. Modules the patch did not
touch are byte-identical to an unpatched run.

## Derived peripherals

An SVD says "UART1 is a UART0 somewhere else" with `derivedFrom`, and their registers are identical by
construction. `--derived expand`, the default, writes those registers out again under the derived
name, so `uart1.CTRL_OFFSET` exists and the datasheet's UART1 section is what a reader can look up. On
the RP2350 that costs 18% more constants — 32,980 against 28,047 — for nine peripherals.

`--derived base-only` emits the derived peripheral's base address and a comment pointing at the
parent's offsets, which is honest about the duplication and smaller.

## What a generated register map costs

Measured 2026-08-10 against sysl 0.0.38 and the RP2350's own SVD — 54 peripherals, 32,980 constants,
41,096 lines of generated sysl.

**Nothing, in the binary.** A program compiled against a 54,000-constant library is byte-identical to
the same program compiled against no library at all. Unused constants do not reach the executable, so
a generous register map costs a chip's flash nothing.

**Something, at compile time — and not what you would guess.** The cost tracks the size of the library
compiled against, *not* what the program imports from it. A program importing one constant pays what a
program importing everything pays:

| the register map, on `--lib` | added to each compile |
|---|---|
| as a source root | **+2.74 s** |
| as a prebuilt `.syslib` | **+0.28 s** |

So one module per peripheral is a readability decision, and a good one, but it buys no compile time —
which is worth stating because the opposite is the natural assumption. What does help is a prebuilt
artifact, and filtering out what nobody wants: on the RP2350, `OTP_DATA_RAW` and `OTP_DATA` together
are 449 registers of one-time-programmable fuse layout.

## Tests

```
sbt test
```

Two of them need something this repository does not carry — a real vendor SVD, and the sysl compiler
on `PATH` — and are cancelled rather than failed when it is absent. Where both are present they are
the most valuable tests here: everything else checks the emitter against this tool's own idea of what
sysl looks like, and only the compiler can say whether that idea is right. `SVD_FILE` overrides where
the SVD is looked for.

## Licence

ISC.
