package sh.sysl.svd

import scala.xml.{Elem, Node, XML}
import java.io.File

/** Reads a CMSIS-SVD file into a `Device`.
  *
  * **What it does not understand, it refuses.** That is the whole design of this file and it is worth
  * stating plainly, because the alternative is tempting and much worse: a parser that ignores an
  * element it was not written for emits a register map that is silently missing registers, and a
  * register map is exactly the kind of artifact nobody proofreads. It gets generated, committed, and
  * believed. A `<dim>` array dropped in silence is thirty-one registers that do not exist as far as
  * every program compiled against it is concerned, and the failure surfaces as hardware not
  * responding — days away from here, and looking nothing like a generator bug.
  *
  * So the unsupported list below is checked explicitly and by name. Adding support for one of them is
  * a small job; discovering you needed it is not.
  */
object Parser:

  /** SVD's `scaledNonNegativeInteger`: decimal, `0x` hex, `#` binary, an optional sign, and an
    * optional binary-magnitude suffix.
    */
  def number(raw: String): Long =
    val s = raw.trim
    if s.isEmpty then throw SvdError("empty number")
    val (body, scale) = s.last match
      case 'k' | 'K' => (s.dropRight(1), 1024L)
      case 'm' | 'M' => (s.dropRight(1), 1024L * 1024)
      case 'g' | 'G' => (s.dropRight(1), 1024L * 1024 * 1024)
      case 't' | 'T' => (s.dropRight(1), 1024L * 1024 * 1024 * 1024)
      case _         => (s, 1L)
    val unsigned = if body.startsWith("+") then body.drop(1) else body
    val value =
      try
        if unsigned.startsWith("0x") || unsigned.startsWith("0X") then java.lang.Long.parseUnsignedLong(unsigned.drop(2), 16)
        else if unsigned.startsWith("#") then java.lang.Long.parseUnsignedLong(unsigned.drop(1).replace("x", "0"), 2)
        else java.lang.Long.parseUnsignedLong(unsigned, 10)
      catch case _: NumberFormatException => throw SvdError(s"'$raw' is not a number SVD can express")
    value * scale

  extension (n: Node)
    private def childText(name: String): Option[String] =
      (n \ name).headOption.map(_.text.trim).filter(_.nonEmpty)

    private def childNum(name: String): Option[Long] = childText(name).map(number)

    private def required(name: String, what: => String): String =
      childText(name).getOrElse(throw SvdError(s"$what has no <$name>"))

  /** Descriptions in a real SVD are indented across several lines, because they sit inside an indented
    * XML document. Every consumer wants them as one line of prose, so that is what is stored.
    */
  private def description(n: Node): Option[String] =
    (n \ "description").headOption.map(_.text.split("\\s+").filter(_.nonEmpty).mkString(" ")).filter(_.nonEmpty)

  /** Elements this tool does not model. Each is refused where it is found, naming the register or
    * field, so the message says what to look at rather than that something somewhere is unsupported.
    */
  private def refuseUnsupported(n: Node, what: String): Unit =
    val unsupported = Seq(
      "dim"               -> "a register or field array",
      "cluster"           -> "a register cluster",
      "alternateGroup"    -> "an alternate register group",
      "alternatePeripheral" -> "an alternate peripheral",
      "writeConstraint"   -> "a write constraint",
      "headerStructName"  -> "a header struct name"
    )
    for (tag, meaning) <- unsupported if (n \ tag).nonEmpty do
      throw SvdError(s"$what uses <$tag> ($meaning), which this tool does not understand — it would be dropped silently, so the run stops instead")

  private def field(n: Node, register: String): Field =
    val name = n.required("name", s"a field of register '$register'")
    val what = s"field '$register.$name'"
    refuseUnsupported(n, what)

    // The same fact has three spellings in the schema. A file may use any of them, and files in the
    // wild differ: Raspberry Pi writes bitRange throughout, most vendors write bitOffset/bitWidth.
    val (offset, width) =
      (n.childText("bitRange"), n.childNum("bitOffset"), n.childNum("bitWidth"), n.childNum("lsb"), n.childNum("msb")) match
        case (Some(range), _, _, _, _) =>
          val m = """\[\s*(\d+)\s*:\s*(\d+)\s*\]""".r.findFirstMatchIn(range)
            .getOrElse(throw SvdError(s"$what has bitRange '$range', which is not '[msb:lsb]'"))
          val (msb, lsb) = (m.group(1).toInt, m.group(2).toInt)
          if msb < lsb then throw SvdError(s"$what has bitRange '$range', whose msb is below its lsb")
          (lsb, msb - lsb + 1)
        case (_, Some(off), Some(w), _, _) => (off.toInt, w.toInt)
        case (_, _, _, Some(lsb), Some(msb)) =>
          if msb < lsb then throw SvdError(s"$what has msb $msb below lsb $lsb")
          (lsb.toInt, (msb - lsb + 1).toInt)
        case _ => throw SvdError(s"$what says nothing about which bits it occupies")

    if width <= 0 then throw SvdError(s"$what is $width bits wide")

    val enums = (n \ "enumeratedValues").flatMap: ev =>
      if (ev \ "@derivedFrom").nonEmpty then
        throw SvdError(s"$what has enumeratedValues derived from another field, which this tool does not understand")
      (ev \ "enumeratedValue").flatMap: e =>
        // An <isDefault/> entry names no value: it says "everything else". Nothing can be emitted for
        // it, so it is skipped rather than refused — unlike the cases above it removes no information.
        e.childNum("value").map: v =>
          EnumeratedValue(e.required("name", s"an enumerated value of $what"), description(e), v)

    Field(name, description(n), offset, width, n.childText("access").flatMap(Access.parse), enums)

  private def register(n: Node, peripheral: String): Register =
    val name = n.required("name", s"a register of peripheral '$peripheral'")
    val what = s"register '$peripheral.$name'"
    refuseUnsupported(n, what)
    Register(
      name          = name,
      description   = description(n),
      addressOffset = n.childNum("addressOffset").getOrElse(throw SvdError(s"$what has no <addressOffset>")),
      size          = n.childNum("size").map(_.toInt),
      access        = n.childText("access").flatMap(Access.parse),
      resetValue    = n.childNum("resetValue"),
      fields        = (n \ "fields" \ "field").map(field(_, name)),
      // Unlike the elements refused above, this one takes nothing away when it is merely noted: the
      // register carrying it is complete, and is emitted like any other.
      alternateOf   = n.childText("alternateRegister")
    )

  private def peripheral(n: Node): Peripheral =
    val name = n.required("name", "a peripheral")
    refuseUnsupported(n, s"peripheral '$name'")
    if (n \ "registers" \ "cluster").nonEmpty then
      throw SvdError(s"peripheral '$name' holds a <cluster>, which this tool does not understand")
    Peripheral(
      name        = name,
      description = description(n),
      baseAddress = n.childNum("baseAddress").getOrElse(throw SvdError(s"peripheral '$name' has no <baseAddress>")),
      derivedFrom = (n \ "@derivedFrom").headOption.map(_.text),
      registers   = (n \ "registers" \ "register").map(register(_, name)),
      interrupts  = (n \ "interrupt").map: i =>
        Interrupt(
          i.required("name", s"an interrupt of peripheral '$name'"),
          description(i),
          i.childNum("value").getOrElse(throw SvdError(s"an interrupt of peripheral '$name' has no <value>")).toInt
        )
    )

  def parse(file: File): Device =
    if !file.exists then throw SvdError(s"${file.getPath} does not exist")
    parse(XML.loadFile(file))

  def parse(root: Elem): Device =
    if root.label != "device" then throw SvdError(s"the document's root is <${root.label}> rather than <device>, so it is not an SVD file")
    val device = Device(
      name              = root.required("name", "the device"),
      description       = description(root),
      width             = root.childNum("width").getOrElse(32L).toInt,
      defaultSize       = root.childNum("size").getOrElse(32L).toInt,
      defaultAccess     = root.childText("access").flatMap(Access.parse),
      defaultResetValue = root.childNum("resetValue"),
      peripherals       = (root \ "peripherals" \ "peripheral").map(peripheral)
    )
    if device.peripherals.isEmpty then throw SvdError("the file declares no peripherals")
    device.resolved // resolve eagerly, so a bad derivedFrom is reported here rather than mid-emission
    device
