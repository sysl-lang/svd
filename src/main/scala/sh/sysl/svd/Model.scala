package sh.sysl.svd

/** The parts of a CMSIS-SVD description this tool understands.
  *
  * It is deliberately not the whole schema. SVD carries a great deal that no register map needs —
  * write constraints, read actions, header struct names — and carrying it here would mean deciding
  * what each one lowers to. What is modelled is what an emitter reads: where a peripheral is, where
  * its registers sit inside it, which bits each field occupies, and what the vendor calls the values
  * a field takes.
  *
  * Everything the schema allows and this does not model is *refused* by the parser rather than
  * dropped. See `Parser`, which explains why that matters more than it sounds.
  */

/** A chip. `defaultSize` and `defaultAccess` are the device-level fallbacks a register inherits when
  * it states neither, which is how nearly every register in a real file is written.
  */
case class Device(
    name: String,
    description: Option[String],
    width: Int,
    defaultSize: Int,
    defaultAccess: Option[Access],
    defaultResetValue: Option[Long],
    peripherals: Seq[Peripheral]
):

  /** By name, for resolving `derivedFrom`. */
  lazy val byName: Map[String, Peripheral] = peripherals.map(p => p.name -> p).toMap

  /** Every peripheral with its inheritance resolved — a derived one gets the registers of whatever it
    * was derived from, keeping its own name, base address and description.
    *
    * A derived peripheral in a real file is how a vendor says "UART1 is a UART0 somewhere else", and
    * the registers really are identical. Whether an emitter then *writes* those registers out again
    * or points the reader at the parent is the emitter's decision, not this one's.
    */
  lazy val resolved: Seq[Peripheral] =
    peripherals.map: p =>
      p.derivedFrom match
        case None         => p
        case Some(parent) =>
          val from = byName.getOrElse(parent, throw SvdError(s"peripheral '${p.name}' derives from '$parent', which this file does not declare"))
          // The schema lets a derived peripheral add or override registers of its own. Taking the
          // parent's list would drop them without a word, so a file that does it is refused instead.
          if p.registers.nonEmpty then
            throw SvdError(s"peripheral '${p.name}' derives from '$parent' and also declares registers of its own, which this tool does not understand — they would be dropped silently, so the run stops instead")
          p.copy(registers = from.registers, interrupts = if p.interrupts.isEmpty then from.interrupts else p.interrupts)

/** One peripheral. `registers` is empty on a derived peripheral until `Device.resolved` fills it in.
  */
case class Peripheral(
    name: String,
    description: Option[String],
    baseAddress: Long,
    derivedFrom: Option[String],
    registers: Seq[Register],
    interrupts: Seq[Interrupt]
)

/** One register. `alternateOf` is SVD's `<alternateRegister>`: the name of another register in the
  * same peripheral that this one is a second reading of — a timer's `CCMR1_Input` beside
  * `CCMR1_Output`, the same word laid out for capture or for compare. Each is a register in its own
  * right, with its own name, offset and fields, so both are kept; the link is only a note for a
  * reader.
  */
case class Register(
    name: String,
    description: Option[String],
    addressOffset: Long,
    size: Option[Int],
    access: Option[Access],
    resetValue: Option[Long],
    fields: Seq[Field],
    alternateOf: Option[String] = None
)

/** A run of bits inside a register. Held as offset and width whatever spelling the file used — SVD
  * allows three, and they are the same fact written three ways.
  */
case class Field(
    name: String,
    description: Option[String],
    bitOffset: Int,
    bitWidth: Int,
    access: Option[Access],
    enumeratedValues: Seq[EnumeratedValue]
):
  /** The field's bits, in place. Wider than 32 bits is possible in principle, so this is a `Long`. */
  def mask: Long = (if bitWidth >= 64 then -1L else (1L << bitWidth) - 1L) << bitOffset

case class EnumeratedValue(name: String, description: Option[String], value: Long)

case class Interrupt(name: String, description: Option[String], value: Int)

enum Access:
  case ReadOnly, WriteOnly, ReadWrite, WriteOnce, ReadWriteOnce

object Access:
  /** The schema's spellings, which are hyphenated and lower case. */
  def parse(s: String): Option[Access] = s.trim match
    case "read-only"      => Some(ReadOnly)
    case "write-only"     => Some(WriteOnly)
    case "read-write"     => Some(ReadWrite)
    case "writeOnce"      => Some(WriteOnce)
    case "read-writeOnce" => Some(ReadWriteOnce)
    case _                => None

/** Anything that stops the tool producing a correct register map.
  *
  * There is one exception type rather than several because every one of these ends the same way: the
  * run stops and the message is printed. A half-written register map is worse than none, since it
  * compiles.
  */
case class SvdError(message: String) extends Exception(message)
