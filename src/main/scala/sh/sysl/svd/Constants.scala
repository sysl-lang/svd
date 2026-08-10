package sh.sysl.svd

/** Emits a peripheral's registers as sysl `const` declarations — one module per peripheral.
  *
  * **One module per peripheral is a readability decision and nothing else.** It was measured on
  * 2026-08-10 against sysl 0.0.38: a program importing nothing from a library pays exactly what a
  * program importing everything pays, and the cost scales with the library rather than with the
  * imports. So splitting buys no compile time. It is still right — a reader wants `io_bank0`, and
  * 28,000 constants in one module is not a thing to attempt — but the argument for it must not be
  * made on speed.
  *
  * What that measurement does buy is the knowledge that **an unused constant is free in the emitted
  * binary**: a program compiled against a 54,000-constant library is byte-identical to one compiled
  * against nothing. A generous register map costs flash nothing.
  */
object Constants:

  case class Options(
      moduleRoot: String = "sh.sysl.rp2350",
      descriptions: Descriptions = Descriptions.Registers,
      absoluteAddresses: Boolean = false,
      derived: Derived = Derived.Expand,
      include: Set[String] = Set.empty,
      exclude: Set[String] = Set.empty
  )

  /** How much of the SVD's prose reaches the generated source.
    *
    * The generated file is committed source, and a reader is owed something. `All` is 0.97 MB of
    * prose on the RP2350 and swamps the declarations; `None` leaves a wall of hex. `Registers` — a
    * line above each register, nothing on fields — is the middle and the default.
    */
  enum Descriptions:
    case None, Registers, All

  /** What to do with a peripheral the SVD derives from another. Their registers are identical by
    * construction, so `BaseOnly` emits the base address and points at the parent, and `Expand` writes
    * the registers out again under the derived name.
    *
    * `Expand` costs 18% more constants on the RP2350 (32,980 against 28,047) and is the default,
    * because `pio1.CTRL_OFFSET` is what somebody reading the datasheet's PIO1 section will look for.
    * `BaseOnly` is honest about the duplication and is there for anyone who would rather have it.
    */
  enum Derived:
    case Expand, BaseOnly

  /** `IO_BANK0` names a module `io_bank0`. Nothing else changes — the datasheet's spelling is kept
    * for every name *inside* the module, so a reader greps what the datasheet shows them.
    */
  def moduleName(peripheral: String): String = peripheral.toLowerCase

  private def hex(v: Long, width: Int): String =
    val digits = if width <= 32 then 8 else 16
    "0x" + String.format(s"%0${digits}x", v)

  /** The integer type every constant takes, from the device's own register width. */
  private def intType(width: Int): String = s"u$width"

  private def comment(text: String, indent: String = ""): Seq[String] =
    // Wrap to something a terminal can read. SVD prose is one long line by the time it reaches here.
    val words = text.split(" ").filter(_.nonEmpty)
    if words.isEmpty then Seq.empty
    else
      val lines = words.foldLeft(Vector(Vector.empty[String])): (acc, w) =>
        val cur = acc.last
        if cur.isEmpty || (cur.map(_.length + 1).sum + w.length) <= 96 then acc.init :+ (cur :+ w)
        else acc :+ Vector(w)
      lines.map(l => s"$indent// ${l.mkString(" ")}")

  /** Which peripherals are emitted, after the include and exclude options.
    *
    * Filtering matters more than it looks: every constant emitted is paid for at compile time by
    * every program that compiles against the package, whether or not it imports the module. On the
    * RP2350 `OTP_DATA_RAW` and `OTP_DATA` are 449 registers of fuse layout that almost nothing wants.
    */
  def selected(device: Device, opts: Options): Seq[Peripheral] =
    val all = opts.derived match
      case Derived.Expand   => device.resolved
      case Derived.BaseOnly => device.peripherals
    all
      .filter(p => opts.include.isEmpty || opts.include.contains(p.name))
      .filterNot(p => opts.exclude.contains(p.name))
      .sortBy(_.name)

  /** One peripheral's module, as text. */
  def module(device: Device, peripheral: Peripheral, opts: Options): String =
    val out  = Vector.newBuilder[String]
    val ty   = intType(device.width)
    val name = moduleName(peripheral.name)

    for d <- peripheral.description do out ++= comment(d)
    out += ""
    out += s"module ${opts.moduleRoot}.$name"
    out += ""

    out += s"// The peripheral's base address. Every offset below is from here."
    out += s"const BASE: $ty = ${hex(peripheral.baseAddress, device.width)}"

    val derivedNote = peripheral.derivedFrom.filter(_ => opts.derived == Derived.BaseOnly)
    derivedNote match
      case Some(parent) =>
        out += ""
        out ++= comment(
          s"This peripheral is a second ${parent}, at a different address: the SVD derives it, so its " +
            s"registers are identical. Use ${moduleName(parent)}'s offsets against the BASE above."
        )
      case None =>
        for r <- peripheral.registers do
          out += ""
          if opts.descriptions != Descriptions.None then for d <- r.description do out ++= comment(d)
          out += s"const ${r.name}_OFFSET: $ty = ${hex(r.addressOffset, device.width)}"
          if opts.absoluteAddresses then
            out += s"const ${r.name}_ADDR: $ty = ${hex(peripheral.baseAddress + r.addressOffset, device.width)}"

          for f <- r.fields do
            if opts.descriptions == Descriptions.All then for d <- f.description do out ++= comment(d)
            out += s"const ${r.name}_${f.name}_MASK: $ty = ${hex(f.mask, device.width)}"
            out += s"const ${r.name}_${f.name}_SHIFT: $ty = ${f.bitOffset}"
            for e <- f.enumeratedValues do
              if opts.descriptions == Descriptions.All then for d <- e.description do out ++= comment(d)
              // Upper-cased, unlike every other name here, which is kept exactly as the SVD spells it.
              // A vendor writes these in whatever case it likes -- Raspberry Pi's GPIO functions are
              // `sio_0` and `jtag_tck` -- and a constant reading GPIO0_CTRL_FUNCSEL_sio_0 next to
              // GPIO0_CTRL_FUNCSEL_MASK looks like a mistake rather than a choice. Checked against the
              // whole RP2350 before this was done: no two enumerated values in any one module differ
              // only in case, so nothing collides.
              out += s"const ${r.name}_${f.name}_${e.name.toUpperCase}: $ty = ${e.value}"

    out.result().mkString("\n").replaceAll("\n{3,}", "\n\n").trim + "\n"

  /** Every selected peripheral, as a module path relative to a library root and its text.
    *
    * The path is the module's own name twice — `io_bank0/io_bank0.sysl` — which is the layout every
    * package in the org uses for a sub-module.
    */
  def emit(device: Device, opts: Options): Seq[(String, String)] =
    val dir = opts.moduleRoot.split('.').mkString("/")
    selected(device, opts).map: p =>
      val n = moduleName(p.name)
      s"$dir/$n/$n.sysl" -> module(device, p, opts)
