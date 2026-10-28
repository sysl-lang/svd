package sh.sysl.svd

import com.typesafe.config.{ConfigException, ConfigFactory, ConfigList, ConfigObject, ConfigParseOptions, ConfigValue, ConfigValueType}
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** A list of corrections to a vendor's SVD, applied to the parsed device before anything is emitted.
  *
  * Vendors' SVDs are wrong in places — a bit missing, a field two bits too narrow, a block of registers
  * left out — and the wrong value is the one every program compiled against the generated map then
  * believes. Editing the generated files is lost at the next regeneration, and editing the vendor's
  * XML hides the change inside a 50,000-line file nobody diffs. A patch file keeps each correction
  * small, named and argued for: every entry carries a `why`, and the modules a patch touched say so
  * in their header, with the file's digest.
  *
  * The operation set follows the one the Rust embedded ecosystem's svdtools settled on for the same
  * problem — add, modify and delete at the peripheral, register and field level, plus copying a run
  * of registers somewhere else under new names — written in HOCON, which is the format of every
  * manifest in the org.
  *
  * **A patch that does not apply is an error, never a warning.** An entry naming something the device
  * does not have is almost always an entry written against a different version of the SVD, and one
  * that changes nothing is a correction the vendor has since made; either way the file is stale, and
  * saying nothing would let it rot. The same goes for a key an entry does not take: a misspelt
  * `bitwidth` silently ignored is a correction silently not made.
  *
  * {{{
  * device = STM32H747_CM7
  * patches = [
  *   { op = modify-field, peripheral = LTDC, register = SSCR, field = HSW
  *     set { bitWidth = 12 }
  *     why = "RM0399 LTDC_SSCR: HSW[11:0] is bits 27:16" }
  * ]
  * }}}
  */
case class Patch(source: String, digest: String, device: Option[String], entries: Seq[Patch.Entry])

object Patch:

  /** One entry: what it does, and the reason, which is required. `number` counts from 1, as a reader
    * of the file would.
    */
  case class Entry(source: String, number: Int, opName: String, why: String, op: Op):
    def where: String = s"$source, entry $number ($opName)"
    def fail(message: String): Nothing = throw SvdError(s"$where: $message")

  case class Rename(from: String, to: String):
    def apply(name: String): String = to + name.drop(from.length)

  enum Op:
    case AddPeripheral(peripheral: Peripheral)
    case ModifyPeripheral(peripheral: String, set: PeripheralChange)
    case DeletePeripheral(peripheral: String)
    case AddRegister(peripheral: String, register: Register)
    case ModifyRegister(peripheral: String, register: String, set: RegisterChange)
    case DeleteRegister(peripheral: String, register: String)
    case AddField(peripheral: String, register: String, field: Field)
    case ModifyField(peripheral: String, register: String, field: String, set: FieldChange)
    case DeleteField(peripheral: String, register: String, field: String)
    /** svdtools' `_derive` of a register block: each named register again, `offset` bytes further on,
      * its name's prefix replaced. A dual-core chip's second copy of a per-core block is the case.
      */
    case CopyRegisters(peripheral: String, registers: Seq[String], offset: Long, prefix: Rename)
    case RenameRegisters(peripheral: String, registers: Seq[String], prefix: Rename)

  case class PeripheralChange(name: Option[String], description: Option[String], baseAddress: Option[Long])

  case class RegisterChange(
      name: Option[String],
      description: Option[String],
      addressOffset: Option[Long],
      size: Option[Int],
      access: Option[Access],
      resetValue: Option[Long]
  )

  case class FieldChange(name: Option[String], description: Option[String], bitOffset: Option[Int], bitWidth: Option[Int], access: Option[Access])

  /** What applying a patch produced: the corrected device, and every `why` that applied to each
    * peripheral, by the peripheral's final name — which is what a module header prints.
    */
  case class Applied(device: Device, patch: Patch, whys: Map[String, Seq[String]])

  // ---------------------------------------------------------------------------------------------
  // Reading

  def load(file: File): Patch =
    if !file.exists then throw SvdError(s"${file.getPath} does not exist")
    val bytes  = Files.readAllBytes(file.toPath)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
    parse(String(bytes, "UTF-8"), file.getPath, digest)

  /** A patch from text. `source` names it in messages and in the generated header. */
  def parse(text: String, source: String, digest: String = ""): Patch =
    val root =
      try ConfigFactory.parseString(text, ConfigParseOptions.defaults.setOriginDescription(source)).root
      catch case e: ConfigException => throw SvdError(s"$source is not readable HOCON: ${e.getMessage}")
    val top     = Reader(root, source)
    val device  = top.str("device")
    val entries = top.list("patches").getOrElse(top.fail("has no 'patches' list"))
    top.finish()
    if entries.isEmpty then top.fail("has an empty 'patches' list, so it corrects nothing")
    Patch(source, digest, device, entries.zipWithIndex.map((v, i) => entry(v, i + 1, source)))

  private def entry(v: ConfigValue, number: Int, source: String): Entry =
    val obj = v match
      case o: ConfigObject => o
      case _               => throw SvdError(s"$source, entry $number: is not an object")
    val opName = Reader(obj, s"$source, entry $number").req("op")
    val r      = Reader(obj, s"$source, entry $number ($opName)")
    r.req("op")
    val why = r.req("why").trim
    if why.isEmpty then r.fail("has an empty 'why'; every correction says what it is checked against")

    def set[A](build: Reader => A): A =
      val s = r.obj("set").getOrElse(r.fail("has no 'set' block"))
      if s.isEmpty then r.fail("has an empty 'set' block, so it changes nothing")
      val sr = Reader(s, s"${r.what}, set")
      val a  = build(sr)
      sr.finish()
      a

    def prefix: Rename =
      val p  = r.obj("prefix").getOrElse(r.fail("has no 'prefix' block"))
      val pr = Reader(p, s"${r.what}, prefix")
      val rn = Rename(pr.req("from"), pr.req("to"))
      pr.finish()
      if rn.from == rn.to then r.fail(s"renames the prefix '${rn.from}' to itself")
      rn

    def names: Seq[String] =
      val n = r.strings("registers").getOrElse(r.fail("has no 'registers' list"))
      if n.isEmpty then r.fail("has an empty 'registers' list")
      if n.distinct.size != n.size then r.fail("names a register twice")
      n

    val op = opName match
      case "add-peripheral" =>
        val p = Reader(r.obj("peripheral").getOrElse(r.fail("has no 'peripheral' block")), s"${r.what}, peripheral")
        val made = Peripheral(p.req("name"), p.str("description"), p.reqLong("baseAddress"), p.str("derivedFrom"), Seq.empty, Seq.empty)
        p.finish()
        Op.AddPeripheral(made)
      case "modify-peripheral" =>
        Op.ModifyPeripheral(r.req("peripheral"), set(s => PeripheralChange(s.str("name"), s.str("description"), s.long("baseAddress"))))
      case "delete-peripheral" => Op.DeletePeripheral(r.req("peripheral"))
      case "add-register" =>
        val p = r.req("peripheral")
        Op.AddRegister(p, register(Reader(r.obj("register").getOrElse(r.fail("has no 'register' block")), s"${r.what}, register")))
      case "modify-register" =>
        Op.ModifyRegister(r.req("peripheral"), r.req("register"), set: s =>
          RegisterChange(s.str("name"), s.str("description"), s.long("addressOffset"), s.int("size"), s.access("access"), s.long("resetValue")))
      case "delete-register" => Op.DeleteRegister(r.req("peripheral"), r.req("register"))
      case "add-field" =>
        val (p, reg) = (r.req("peripheral"), r.req("register"))
        Op.AddField(p, reg, field(Reader(r.obj("field").getOrElse(r.fail("has no 'field' block")), s"${r.what}, field")))
      case "modify-field" =>
        Op.ModifyField(r.req("peripheral"), r.req("register"), r.req("field"), set: s =>
          FieldChange(s.str("name"), s.str("description"), s.int("bitOffset"), s.int("bitWidth"), s.access("access")))
      case "delete-field" => Op.DeleteField(r.req("peripheral"), r.req("register"), r.req("field"))
      case "copy-registers" =>
        val p = r.req("peripheral")
        Op.CopyRegisters(p, names, r.reqLong("offset"), prefix)
      case "rename-registers" =>
        val p = r.req("peripheral")
        Op.RenameRegisters(p, names, prefix)
      case other =>
        r.fail(s"'$other' is not an operation; the operations are add-, modify- and delete- of a peripheral, register or field, copy-registers and rename-registers")
    r.finish()
    Entry(source, number, opName, why, op)

  private def field(f: Reader): Field =
    val made = Field(f.req("name"), f.str("description"), f.reqInt("bitOffset"), f.reqInt("bitWidth"), f.access("access"), Seq.empty)
    f.finish()
    if made.bitWidth <= 0 then f.fail(s"is ${made.bitWidth} bits wide")
    made

  private def register(g: Reader): Register =
    val fields = g.objects("fields").getOrElse(Seq.empty).zipWithIndex.map((o, i) => field(Reader(o, s"${g.what}, field ${i + 1}")))
    val made = Register(
      name          = g.req("name"),
      description   = g.str("description"),
      addressOffset = g.reqLong("addressOffset"),
      size          = g.int("size"),
      access        = g.access("access"),
      resetValue    = g.long("resetValue"),
      fields        = fields,
      alternateOf   = g.str("alternateOf")
    )
    g.finish()
    made

  /** Reads one HOCON object, remembering which keys were asked for so that `finish` can refuse the
    * rest: a key nobody reads is a correction nobody makes.
    */
  private final class Reader(obj: ConfigObject, val what: String):
    private val asked = mutable.Set.empty[String]

    def fail(message: String): Nothing = throw SvdError(s"$what: $message")

    private def value(key: String): Option[ConfigValue] =
      asked += key
      Option(obj.get(key))

    private def kind(v: ConfigValue): String = v.valueType.toString.toLowerCase

    def str(key: String): Option[String] = value(key).map: v =>
      v.valueType match
        case ConfigValueType.STRING | ConfigValueType.NUMBER => v.unwrapped.toString
        case _ => fail(s"'$key' is a ${kind(v)}, not a string")

    def req(key: String): String = str(key).getOrElse(fail(s"has no '$key'"))

    /** A whole number, written either as a HOCON number or as a string in SVD's own syntax — which is
      * how hex is written, since HOCON has no hex literal: `addressOffset = "0xC0"`.
      */
    def long(key: String): Option[Long] = value(key).map: v =>
      v.valueType match
        case ConfigValueType.NUMBER =>
          v.unwrapped match
            case n: java.lang.Integer => n.longValue
            case n: java.lang.Long    => n.longValue
            case _                    => fail(s"'$key' is ${v.render}, which is not a whole number")
        case ConfigValueType.STRING =>
          val s = v.unwrapped.toString.trim
          try if s.startsWith("-") then -Parser.number(s.drop(1)) else Parser.number(s)
          catch case e: SvdError => fail(s"'$key': ${e.message}")
        case _ => fail(s"'$key' is a ${kind(v)}, not a number")

    def reqLong(key: String): Long = long(key).getOrElse(fail(s"has no '$key'"))

    def int(key: String): Option[Int] = long(key).map: n =>
      if n < 0 || n > Int.MaxValue then fail(s"'$key' is $n, which is out of range") else n.toInt

    def reqInt(key: String): Int = int(key).getOrElse(fail(s"has no '$key'"))

    def access(key: String): Option[Access] = str(key).map: s =>
      Access.parse(s).getOrElse(fail(s"'$key' is '$s', which is not an SVD access (read-only, write-only, read-write, writeOnce, read-writeOnce)"))

    def obj(key: String): Option[ConfigObject] = value(key).map:
      case o: ConfigObject => o
      case v               => fail(s"'$key' is a ${kind(v)}, not an object")

    def list(key: String): Option[Seq[ConfigValue]] = value(key).map:
      case l: ConfigList => l.asScala.toSeq
      case v             => fail(s"'$key' is a ${kind(v)}, not a list")

    def strings(key: String): Option[Seq[String]] = list(key).map: vs =>
      vs.map: v =>
        v.valueType match
          case ConfigValueType.STRING | ConfigValueType.NUMBER => v.unwrapped.toString
          case _ => fail(s"'$key' holds a ${kind(v)}, not a name")

    def objects(key: String): Option[Seq[ConfigObject]] = list(key).map: vs =>
      vs.map:
        case o: ConfigObject => o
        case v               => fail(s"'$key' holds a ${kind(v)}, not an object")

    def finish(): Unit =
      val extra = obj.keySet.asScala.toSeq.filterNot(asked).sorted
      if extra.nonEmpty then fail(s"has ${extra.map(k => s"'$k'").mkString(", ")}, which it does not take")

  // ---------------------------------------------------------------------------------------------
  // Applying

  def applyTo(device: Device, patch: Patch): Applied =
    for d <- patch.device if d != device.name do
      throw SvdError(s"${patch.source} is written for $d, and this SVD describes ${device.name}")
    val whys = mutable.LinkedHashMap.empty[String, Vector[String]]
    def note(peripheral: String, why: String): Unit = whys(peripheral) = whys.getOrElse(peripheral, Vector.empty) :+ why
    val patched = patch.entries.foldLeft(device): (d, e) =>
      val (next, touched) = step(d, e)
      e.op match
        case Op.ModifyPeripheral(old, PeripheralChange(Some(renamed), _, _)) =>
          whys.remove(old).foreach(w => whys(renamed) = w)
          note(touched, e.why)
        // A deleted peripheral has no module to explain itself in.
        case Op.DeletePeripheral(gone) => whys.remove(gone)
        case _                         => note(touched, e.why)
      next
    // Resolve now, so a patch that breaks derivation is reported against the patch rather than later.
    try patched.resolved
    catch case e: SvdError => throw SvdError(s"${patch.source} leaves the device inconsistent: ${e.message}")
    Applied(patched, patch, whys.toMap)

  /** One entry applied: the new device, and the name of the peripheral whose module it changed. */
  private def step(d: Device, e: Entry): (Device, String) =
    def peripheral(name: String): Peripheral =
      d.byName.getOrElse(name, e.fail(s"${d.name} has no peripheral '$name'"))

    // A register-level change to a derived peripheral would be a register of its own, which the
    // derivation then hides; the correction belongs on the peripheral that owns the registers.
    def owner(name: String): Peripheral =
      val p = peripheral(name)
      for parent <- p.derivedFrom do
        e.fail(s"peripheral '$name' is derived from '$parent', so its registers are $parent's; patch $parent, and $name follows")
      p

    def register(p: Peripheral, name: String): Register =
      p.registers.find(_.name == name).getOrElse(e.fail(s"peripheral '${p.name}' has no register '$name'"))

    def field(p: Peripheral, r: Register, name: String): Field =
      r.fields.find(_.name == name).getOrElse(e.fail(s"register '${p.name}.${r.name}' has no field '$name'"))

    def replace(p: Peripheral): Device = d.copy(peripherals = d.peripherals.map(q => if q.name == p.name then p else q))

    def withRegisters(p: Peripheral, regs: Seq[Register]): Device = replace(p.copy(registers = regs))

    def checkField(p: Peripheral, r: Register, f: Field): Unit =
      val size = r.size.getOrElse(d.defaultSize)
      if f.bitWidth <= 0 then e.fail(s"field '${p.name}.${r.name}.${f.name}' would be ${f.bitWidth} bits wide")
      if f.bitOffset < 0 || f.bitOffset + f.bitWidth > size then
        e.fail(s"field '${p.name}.${r.name}.${f.name}' would occupy bits ${f.bitOffset + f.bitWidth - 1}:${f.bitOffset}, outside a $size-bit register")
      for other <- r.fields if other.name != f.name && (other.mask & f.mask) != 0 do
        e.fail(s"field '${p.name}.${r.name}.${f.name}' would overlap '${other.name}' (bits ${other.bitOffset + other.bitWidth - 1}:${other.bitOffset})")

    // Two registers at one offset are legitimate only when one says it is an alternate reading of
    // the other; anything else a patch puts there is a slip.
    def checkOffset(p: Peripheral, regs: Seq[Register], r: Register): Unit =
      if r.alternateOf.isEmpty then
        for other <- regs if other.name != r.name && other.addressOffset == r.addressOffset && other.alternateOf.isEmpty do
          e.fail(f"register '${p.name}.${r.name}' would sit at offset 0x${r.addressOffset}%x, which '${other.name}' already holds")

    def unchanged(what: String): Nothing = e.fail(s"changes nothing: $what already reads that way")

    e.op match
      case Op.AddPeripheral(p) =>
        if d.byName.contains(p.name) then e.fail(s"${d.name} already has a peripheral '${p.name}'")
        for parent <- p.derivedFrom do peripheral(parent)
        (d.copy(peripherals = d.peripherals :+ p), p.name)

      case Op.ModifyPeripheral(name, c) =>
        val p = peripheral(name)
        val q = p.copy(name = c.name.getOrElse(p.name), description = c.description.orElse(p.description), baseAddress = c.baseAddress.getOrElse(p.baseAddress))
        if q == p then unchanged(s"peripheral '$name'")
        if q.name != p.name && d.byName.contains(q.name) then e.fail(s"${d.name} already has a peripheral '${q.name}'")
        // A rename carries every derivation from the old name with it.
        val peripherals = d.peripherals.map: o =>
          if o.name == p.name then q
          else if o.derivedFrom.contains(p.name) then o.copy(derivedFrom = Some(q.name))
          else o
        (d.copy(peripherals = peripherals), q.name)

      case Op.DeletePeripheral(name) =>
        peripheral(name)
        for child <- d.peripherals if child.derivedFrom.contains(name) do
          e.fail(s"peripheral '${child.name}' is derived from '$name'; delete or re-base it first")
        (d.copy(peripherals = d.peripherals.filterNot(_.name == name)), name)

      case Op.AddRegister(pn, r) =>
        val p = owner(pn)
        if p.registers.exists(_.name == r.name) then e.fail(s"peripheral '$pn' already has a register '${r.name}'")
        for f <- r.fields do checkField(p, r, f)
        checkOffset(p, p.registers, r)
        (withRegisters(p, p.registers :+ r), pn)

      case Op.ModifyRegister(pn, rn, c) =>
        val p = owner(pn)
        val r = register(p, rn)
        val q = r.copy(
          name          = c.name.getOrElse(r.name),
          description   = c.description.orElse(r.description),
          addressOffset = c.addressOffset.getOrElse(r.addressOffset),
          size          = c.size.orElse(r.size),
          access        = c.access.orElse(r.access),
          resetValue    = c.resetValue.orElse(r.resetValue)
        )
        if q == r then unchanged(s"register '$pn.$rn'")
        if q.name != r.name && p.registers.exists(_.name == q.name) then e.fail(s"peripheral '$pn' already has a register '${q.name}'")
        if q.addressOffset != r.addressOffset then checkOffset(p, p.registers, q)
        for f <- q.fields do checkField(p, q, f)
        (withRegisters(p, renamed(p.registers.map(o => if o.name == rn then q else o), Map(rn -> q.name))), pn)

      case Op.DeleteRegister(pn, rn) =>
        val p = owner(pn)
        register(p, rn)
        (withRegisters(p, p.registers.filterNot(_.name == rn)), pn)

      case Op.AddField(pn, rn, f) =>
        val p = owner(pn)
        val r = register(p, rn)
        if r.fields.exists(_.name == f.name) then e.fail(s"register '$pn.$rn' already has a field '${f.name}'")
        checkField(p, r, f)
        val q = r.copy(fields = (r.fields :+ f).sortBy(_.bitOffset))
        (withRegisters(p, p.registers.map(o => if o.name == rn then q else o)), pn)

      case Op.ModifyField(pn, rn, fn, c) =>
        val p = owner(pn)
        val r = register(p, rn)
        val f = field(p, r, fn)
        val g = f.copy(
          name        = c.name.getOrElse(f.name),
          description = c.description.orElse(f.description),
          bitOffset   = c.bitOffset.getOrElse(f.bitOffset),
          bitWidth    = c.bitWidth.getOrElse(f.bitWidth),
          access      = c.access.orElse(f.access)
        )
        if g == f then unchanged(s"field '$pn.$rn.$fn'")
        if g.name != f.name && r.fields.exists(_.name == g.name) then e.fail(s"register '$pn.$rn' already has a field '${g.name}'")
        // Checked against the register's other fields, with this one's old self taken out.
        checkField(p, r.copy(fields = r.fields.filterNot(_.name == fn)), g)
        val q = r.copy(fields = r.fields.map(o => if o.name == fn then g else o))
        (withRegisters(p, p.registers.map(o => if o.name == rn then q else o)), pn)

      case Op.DeleteField(pn, rn, fn) =>
        val p = owner(pn)
        val r = register(p, rn)
        field(p, r, fn)
        val q = r.copy(fields = r.fields.filterNot(_.name == fn))
        (withRegisters(p, p.registers.map(o => if o.name == rn then q else o)), pn)

      case Op.CopyRegisters(pn, names, offset, prefix) =>
        val p = owner(pn)
        val sources = names.map(register(p, _))
        val copies = sources.map: r =>
          if !r.name.startsWith(prefix.from) then e.fail(s"register '$pn.${r.name}' does not start with '${prefix.from}'")
          if r.addressOffset + offset < 0 then e.fail(s"register '$pn.${r.name}' moved by $offset would sit below the peripheral's base")
          val nameMap = names.map(n => n -> prefix(n)).toMap
          r.copy(name = prefix(r.name), addressOffset = r.addressOffset + offset, alternateOf = r.alternateOf.map(a => nameMap.getOrElse(a, a)))
        for c <- copies do
          if p.registers.exists(_.name == c.name) then e.fail(s"peripheral '$pn' already has a register '${c.name}'")
          checkOffset(p, p.registers ++ copies, c)
        // Each copy sits after the last register below its offset, so the module reads in address order.
        (withRegisters(p, (p.registers ++ copies).sortBy(_.addressOffset)), pn)

      case Op.RenameRegisters(pn, names, prefix) =>
        val p = owner(pn)
        for n <- names do
          register(p, n)
          if !n.startsWith(prefix.from) then e.fail(s"register '$pn.$n' does not start with '${prefix.from}'")
        val map = names.map(n => n -> prefix(n)).toMap
        for (_, to) <- map if p.registers.exists(r => r.name == to && !map.contains(r.name)) do
          e.fail(s"peripheral '$pn' already has a register '$to'")
        (withRegisters(p, renamed(p.registers, map)), pn)

  /** Renames registers, carrying every `alternateRegister` that named one with it. */
  private def renamed(regs: Seq[Register], map: Map[String, String]): Seq[Register] =
    regs.map(r => r.copy(name = map.getOrElse(r.name, r.name), alternateOf = r.alternateOf.map(a => map.getOrElse(a, a))))
