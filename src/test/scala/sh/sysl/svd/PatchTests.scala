package sh.sysl.svd

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import java.io.File
import scala.xml.XML

class PatchTests extends AnyFreeSpec with Matchers:

  private def tiny: Device = Parser.parse(XML.load(getClass.getResourceAsStream("/tiny.svd")))
  private val opts = Constants.Options(moduleRoot = "sh.sysl.tiny")

  private def applied(entries: String, device: Device = tiny): Patch.Applied =
    Patch.applyTo(device, Patch.parse(s"patches = [\n$entries\n]", "t.hocon", "d1g"))
  private def patched(entries: String): Device = applied(entries).device
  private def refused(entries: String): String = intercept[SvdError](applied(entries)).message
  private def unreadable(text: String): String = intercept[SvdError](Patch.parse(text, "t.hocon")).message

  private def reg(d: Device, p: String, r: String): Register = d.byName(p).registers.find(_.name == r).get
  private def fld(d: Device, p: String, r: String, f: String): Field = reg(d, p, r).fields.find(_.name == f).get

  "reading a patch file" - {
    "computes the file's SHA-256 and keeps the path it was given" in {
      val p = Patch.load(File(getClass.getResource("/tiny.patch.hocon").toURI))
      p.digest shouldBe "f72a01fb2f7762e14cc334a12d7cccaa8067eb4b141afbee30128a453022bfbb"
      p.source should endWith("tiny.patch.hocon")
      p.device shouldBe Some("TINY")
      p.entries.map(_.opName) shouldBe Seq("modify-field", "add-field")
    }
    "takes hex as a quoted string or bare, and decimal as a HOCON number" in {
      val d = patched("""{ op = add-register, peripheral = UART0, register { name = A, addressOffset = "0x10" }, why = w }
                        |{ op = add-register, peripheral = UART0, register { name = B, addressOffset = 0x14 }, why = w }
                        |{ op = add-register, peripheral = UART0, register { name = C, addressOffset = 24 }, why = w }""".stripMargin)
      Seq("A", "B", "C").map(reg(d, "UART0", _).addressOffset) shouldBe Seq(0x10L, 0x14L, 24L)
    }
    "refuses an entry with no why" in {
      unreadable("patches = [{ op = delete-peripheral, peripheral = WIDE }]") should include("entry 1 (delete-peripheral): has no 'why'")
    }
    "refuses an empty why" in {
      unreadable("""patches = [{ op = delete-peripheral, peripheral = WIDE, why = "  " }]""") should include("has an empty 'why'")
    }
    "refuses an operation it does not know" in {
      unreadable("patches = [{ op = frobnicate, why = w }]") should include("'frobnicate' is not an operation")
    }
    "refuses a key the operation does not take, since a misspelt key is a correction not made" in {
      unreadable("patches = [{ op = modify-field, peripheral = P, register = R, field = F, set { bitwidth = 3 }, why = w }]") should
        include("set: has 'bitwidth', which it does not take")
      unreadable("patches = [{ op = delete-peripheral, peripheral = WIDE, why = w, colour = red }]") should include("has 'colour'")
      unreadable("patches = [], version = 2") should include("has 'version'")
    }
    "refuses a file with no patches, or an empty list of them" in {
      unreadable("device = TINY") should include("has no 'patches' list")
      unreadable("patches = []") should include("empty 'patches' list")
    }
    "refuses a modify with nothing to set" in {
      unreadable("patches = [{ op = modify-register, peripheral = UART0, register = CTRL, set {}, why = w }]") should include("empty 'set' block")
      unreadable("patches = [{ op = modify-register, peripheral = UART0, register = CTRL, why = w }]") should include("has no 'set' block")
    }
    "refuses an access SVD does not have" in {
      unreadable("patches = [{ op = modify-field, peripheral = P, register = R, field = F, set { access = sometimes }, why = w }]") should
        include("'access' is 'sometimes'")
    }
    "refuses a value of the wrong shape" in {
      unreadable("patches = [{ op = modify-register, peripheral = UART0, register = CTRL, set { addressOffset = [1] }, why = w }]") should
        include("'addressOffset' is a list, not a number")
      unreadable("patches = [{ op = modify-register, peripheral = UART0, register = CTRL, set { addressOffset = 1.5 }, why = w }]") should
        include("not a whole number")
    }
    "refuses text that is not HOCON" in {
      unreadable("patches = [ {") should include("is not readable HOCON")
    }
    "refuses a patch written for another device" in {
      intercept[SvdError](Patch.applyTo(tiny, Patch.parse("device = OTHER\npatches = [{ op = delete-peripheral, peripheral = WIDE, why = w }]", "t.hocon")))
        .message should include("written for OTHER, and this SVD describes TINY")
    }
  }

  "fields" - {
    "add-field puts a field in, in bit order" in {
      val d = patched("{ op = add-field, peripheral = UART0, register = CTRL, field { name = MODE, bitOffset = 1, bitWidth = 1, access = read-only }, why = w }")
      reg(d, "UART0", "CTRL").fields.map(_.name) shouldBe Seq("ENABLE", "MODE", "PARITY")
      fld(d, "UART0", "CTRL", "MODE").access shouldBe Some(Access.ReadOnly)
    }
    "add-field refuses a field that is already there" in {
      refused("{ op = add-field, peripheral = UART0, register = CTRL, field { name = ENABLE, bitOffset = 9, bitWidth = 1 }, why = w }") should
        include("already has a field 'ENABLE'")
    }
    "add-field refuses a field overlapping another" in {
      refused("{ op = add-field, peripheral = UART0, register = CTRL, field { name = X, bitOffset = 3, bitWidth = 2 }, why = w }") should
        include("would overlap 'PARITY' (bits 3:2)")
    }
    "add-field refuses a field running off the end of its register" in {
      refused("{ op = add-field, peripheral = UART0, register = CTRL, field { name = X, bitOffset = 30, bitWidth = 4 }, why = w }") should
        include("bits 33:30, outside a 32-bit register")
    }
    "add-field refuses a register that is not there" in {
      refused("{ op = add-field, peripheral = UART0, register = NOPE, field { name = X, bitOffset = 9, bitWidth = 1 }, why = w }") should
        include("peripheral 'UART0' has no register 'NOPE'")
    }
    "modify-field widens a field and keeps everything else about it" in {
      val f = fld(patched("{ op = modify-field, peripheral = UART0, register = CTRL, field = PARITY, set { bitWidth = 4 }, why = w }"), "UART0", "CTRL", "PARITY")
      (f.bitOffset, f.bitWidth, f.enumeratedValues.size) shouldBe (2, 4, 3)
    }
    "modify-field renames, moves and re-describes" in {
      val d = patched("""{ op = modify-field, peripheral = UART0, register = DATA, field = BYTE
                        |  set { name = CHAR, bitOffset = 8, description = "the character", access = write-only }, why = w }""".stripMargin)
      fld(d, "UART0", "DATA", "CHAR") shouldBe Field("CHAR", Some("the character"), 8, 8, Some(Access.WriteOnly), Seq.empty)
    }
    "modify-field refuses a change that changes nothing" in {
      refused("{ op = modify-field, peripheral = UART0, register = CTRL, field = PARITY, set { bitWidth = 2 }, why = w }") should
        include("changes nothing: field 'UART0.CTRL.PARITY' already reads that way")
    }
    "modify-field refuses a field that is not there" in {
      refused("{ op = modify-field, peripheral = UART0, register = CTRL, field = NOPE, set { bitWidth = 2 }, why = w }") should
        include("register 'UART0.CTRL' has no field 'NOPE'")
    }
    "modify-field refuses widening over a neighbour" in {
      refused("{ op = modify-field, peripheral = UART0, register = CTRL, field = ENABLE, set { bitWidth = 3 }, why = w }") should
        include("would overlap 'PARITY'")
    }
    "modify-field refuses a rename onto a name already taken" in {
      refused("{ op = modify-field, peripheral = UART0, register = CTRL, field = ENABLE, set { name = PARITY }, why = w }") should
        include("already has a field 'PARITY'")
    }
    "delete-field takes a field out" in {
      reg(patched("{ op = delete-field, peripheral = UART0, register = CTRL, field = ENABLE, why = w }"), "UART0", "CTRL").fields.map(_.name) shouldBe Seq("PARITY")
    }
    "delete-field refuses a field that is not there" in {
      refused("{ op = delete-field, peripheral = UART0, register = CTRL, field = NOPE, why = w }") should include("has no field 'NOPE'")
    }
  }

  "registers" - {
    "add-register puts a register in, with its fields" in {
      val d = patched("""{ op = add-register, peripheral = UART0
                        |  register { name = STAT, addressOffset = "0x8", description = "Status", access = read-only, resetValue = 0
                        |             fields = [ { name = BUSY, bitOffset = 0, bitWidth = 1 }, { name = ERR, bitOffset = 1, bitWidth = 2 } ] }
                        |  why = w }""".stripMargin)
      val r = reg(d, "UART0", "STAT")
      (r.addressOffset, r.access, r.fields.map(_.mask)) shouldBe (8L, Some(Access.ReadOnly), Seq(1L, 6L))
    }
    "add-register refuses a register that is already there" in {
      refused("{ op = add-register, peripheral = UART0, register { name = DATA, addressOffset = 8 }, why = w }") should include("already has a register 'DATA'")
    }
    "add-register refuses an offset another register holds, unless it is an alternate" in {
      refused("{ op = add-register, peripheral = UART0, register { name = X, addressOffset = 4 }, why = w }") should
        include("would sit at offset 0x4, which 'DATA' already holds")
      reg(patched("{ op = add-register, peripheral = UART0, register { name = X, addressOffset = 4, alternateOf = DATA }, why = w }"), "UART0", "X")
        .alternateOf shouldBe Some("DATA")
    }
    "add-register refuses fields that overlap each other" in {
      refused("""{ op = add-register, peripheral = UART0
                |  register { name = X, addressOffset = 8, fields = [ { name = A, bitOffset = 0, bitWidth = 2 }, { name = B, bitOffset = 1, bitWidth = 1 } ] }
                |  why = w }""".stripMargin) should include("would overlap")
    }
    "add-register refuses a peripheral that is not there" in {
      refused("{ op = add-register, peripheral = NOPE, register { name = X, addressOffset = 8 }, why = w }") should include("TINY has no peripheral 'NOPE'")
    }
    "a register change on a derived peripheral is refused and pointed at the parent" in {
      refused("{ op = delete-register, peripheral = UART1, register = DATA, why = w }") should
        include("peripheral 'UART1' is derived from 'UART0', so its registers are UART0's; patch UART0")
    }
    "modify-register renames, and an alternate naming it follows" in {
      val d = patched("""{ op = add-register, peripheral = UART0, register { name = DATA_ALT, addressOffset = 4, alternateOf = DATA }, why = w }
                        |{ op = modify-register, peripheral = UART0, register = DATA, set { name = DR }, why = w }""".stripMargin)
      reg(d, "UART0", "DATA_ALT").alternateOf shouldBe Some("DR")
      reg(d, "UART0", "DR").fields.map(_.name) shouldBe Seq("BYTE")
    }
    "modify-register moves a register, and refuses moving it onto another" in {
      reg(patched("{ op = modify-register, peripheral = UART0, register = DATA, set { addressOffset = 0x20, size = 16 }, why = w }"), "UART0", "DATA")
        .addressOffset shouldBe 0x20L
      refused("{ op = modify-register, peripheral = UART0, register = DATA, set { addressOffset = 0 }, why = w }") should include("which 'CTRL' already holds")
    }
    "modify-register refuses shrinking a register under its fields" in {
      refused("{ op = modify-register, peripheral = UART0, register = DATA, set { size = 4 }, why = w }") should include("outside a 4-bit register")
    }
    "modify-register refuses a change that changes nothing" in {
      refused("{ op = modify-register, peripheral = UART0, register = CTRL, set { description = Control }, why = w }") should
        include("changes nothing: register 'UART0.CTRL'")
    }
    "delete-register takes a register out, and refuses one that is not there" in {
      patched("{ op = delete-register, peripheral = UART0, register = DATA, why = w }").byName("UART0").registers.map(_.name) shouldBe Seq("CTRL")
      refused("{ op = delete-register, peripheral = UART0, register = NOPE, why = w }") should include("has no register 'NOPE'")
    }
  }

  "a block of registers" - {
    // The dual-core case: a per-core block of registers, written once in the vendor's file, exists
    // again for the second core at a fixed distance.
    val block = """{ op = add-register, peripheral = WIDE, register { name = C1MASK, addressOffset = "0x80", fields = [ { name = M, bitOffset = 0, bitWidth = 4 } ] }, why = w }
                  |{ op = add-register, peripheral = WIDE, register { name = C1PEND, addressOffset = "0x84" }, why = w }""".stripMargin

    "copy-registers copies each register further on, under the new prefix, fields and all" in {
      val d = patched(block + """
        |{ op = copy-registers, peripheral = WIDE, registers = [C1MASK, C1PEND], offset = "0x40", prefix { from = C1, to = C2 }, why = w }""".stripMargin)
      d.byName("WIDE").registers.map(r => r.name -> r.addressOffset) shouldBe
        Seq("ALL" -> 0L, "C1MASK" -> 0x80L, "C1PEND" -> 0x84L, "C2MASK" -> 0xc0L, "C2PEND" -> 0xc4L)
      reg(d, "WIDE", "C2MASK").fields shouldBe reg(d, "WIDE", "C1MASK").fields
    }
    "copy-registers refuses a register without the prefix" in {
      refused(block + "\n{ op = copy-registers, peripheral = WIDE, registers = [ALL], offset = 0x40, prefix { from = C1, to = C2 }, why = w }") should
        include("register 'WIDE.ALL' does not start with 'C1'")
    }
    "copy-registers refuses copies that collide with a name or an offset" in {
      refused(block + "\n{ op = copy-registers, peripheral = WIDE, registers = [C1MASK], offset = 4, prefix { from = C1, to = C2 }, why = w }") should
        include("would sit at offset 0x84, which 'C1PEND' already holds")
      refused(block + "\n{ op = copy-registers, peripheral = WIDE, registers = [C1MASK], offset = 0x10, prefix { from = C1MASK, to = C1PEND }, why = w }") should
        include("already has a register 'C1PEND'")
    }
    "copy-registers refuses an absent register, an empty list, and a repeated name" in {
      refused(block + "\n{ op = copy-registers, peripheral = WIDE, registers = [C1NOPE], offset = 0x40, prefix { from = C1, to = C2 }, why = w }") should
        include("has no register 'C1NOPE'")
      refused("{ op = copy-registers, peripheral = WIDE, registers = [], offset = 0x40, prefix { from = C1, to = C2 }, why = w }") should
        include("empty 'registers' list")
      refused("{ op = copy-registers, peripheral = WIDE, registers = [A, A], offset = 0x40, prefix { from = C1, to = C2 }, why = w }") should
        include("names a register twice")
    }
    "rename-registers renames a block by its prefix" in {
      val d = patched(block + "\n{ op = rename-registers, peripheral = WIDE, registers = [C1MASK, C1PEND], prefix { from = C1, to = CPU1 }, why = w }")
      d.byName("WIDE").registers.map(_.name) shouldBe Seq("ALL", "CPU1MASK", "CPU1PEND")
    }
    "rename-registers refuses a prefix renamed to itself, and a name already taken" in {
      refused("{ op = rename-registers, peripheral = WIDE, registers = [ALL], prefix { from = A, to = A }, why = w }") should include("to itself")
      refused(block + "\n{ op = rename-registers, peripheral = WIDE, registers = [C1MASK], prefix { from = C1MASK, to = C1PEND }, why = w }") should
        include("already has a register 'C1PEND'")
    }
  }

  "peripherals" - {
    "add-peripheral adds one, which registers can then be added to" in {
      val d = patched("""{ op = add-peripheral, peripheral { name = DSI, baseAddress = "0x50000000", description = "Display" }, why = w }
                        |{ op = add-register, peripheral = DSI, register { name = CR, addressOffset = 0 }, why = w }""".stripMargin)
      d.byName("DSI").baseAddress shouldBe 0x50000000L
      d.byName("DSI").registers.map(_.name) shouldBe Seq("CR")
    }
    "add-peripheral can derive, and the derived one takes its parent's registers" in {
      val d = patched("""{ op = add-peripheral, peripheral { name = UART2, baseAddress = "0x40080000", derivedFrom = UART0 }, why = w }""")
      d.resolved.find(_.name == "UART2").get.registers.map(_.name) shouldBe Seq("CTRL", "DATA")
    }
    "add-peripheral refuses a name taken and a parent absent" in {
      refused("{ op = add-peripheral, peripheral { name = WIDE, baseAddress = 0 }, why = w }") should include("already has a peripheral 'WIDE'")
      refused("{ op = add-peripheral, peripheral { name = U, baseAddress = 0, derivedFrom = NOPE }, why = w }") should include("has no peripheral 'NOPE'")
    }
    "modify-peripheral renames, and what derived from the old name follows" in {
      val d = patched("{ op = modify-peripheral, peripheral = UART0, set { name = USART0 }, why = w }")
      d.byName("UART1").derivedFrom shouldBe Some("USART0")
      d.resolved.find(_.name == "UART1").get.registers.map(_.name) shouldBe Seq("CTRL", "DATA")
    }
    "modify-peripheral moves one, and refuses a change that changes nothing" in {
      patched("{ op = modify-peripheral, peripheral = WIDE, set { baseAddress = \"0x50001000\" }, why = w }").byName("WIDE").baseAddress shouldBe 0x50001000L
      refused("{ op = modify-peripheral, peripheral = WIDE, set { baseAddress = \"0x50000000\" }, why = w }") should include("changes nothing")
    }
    "delete-peripheral takes one out, and refuses one others derive from, or one not there" in {
      patched("{ op = delete-peripheral, peripheral = WIDE, why = w }").peripherals.map(_.name) shouldBe Seq("UART0", "UART1")
      refused("{ op = delete-peripheral, peripheral = UART0, why = w }") should include("'UART1' is derived from 'UART0'")
      refused("{ op = delete-peripheral, peripheral = NOPE, why = w }") should include("has no peripheral 'NOPE'")
    }
  }

  "an error names the file and the entry" in {
    refused("{ op = delete-peripheral, peripheral = WIDE, why = w }\n{ op = delete-peripheral, peripheral = WIDE, why = w }") should
      startWith("t.hocon, entry 2 (delete-peripheral): TINY has no peripheral 'WIDE'")
  }

  "the generated module" - {
    val text = """{ op = modify-field, peripheral = UART0, register = CTRL, field = PARITY, set { bitWidth = 3 }, why = "the datasheet gives PARITY bits 4:2" }"""

    "says which file corrected it, with its digest and every reason" in {
      val a = applied(text)
      Constants.module(a.device, a.device.byName("UART0"), opts.copy(patched = Some(a))) should startWith(
        """// A serial port
          |//
          |// Corrected by t.hocon
          |// (sha256 d1g):
          |// - the datasheet gives PARITY bits 4:2
          |
          |module sh.sysl.tiny.uart0""".stripMargin)
    }
    "indents a reason that wraps under its own dash" in {
      val long = "word " * 30
      val a = applied(s"""{ op = delete-field, peripheral = WIDE, register = ALL, field = EVERY, why = "${long.trim}" }""")
      val lines = Constants.module(a.device, a.device.byName("WIDE"), opts.copy(patched = Some(a))).linesIterator.toSeq
      lines.find(_.startsWith("// - word")) shouldBe defined
      lines.find(_.startsWith("//   word")) shouldBe defined
      lines.filter(_.startsWith("// word")) shouldBe empty
    }
    "carries the corrected value" in {
      val a = applied(text)
      Constants.module(a.device, a.device.byName("UART0"), opts.copy(patched = Some(a))) should include("const CTRL_PARITY_MASK: u32 = 0x0000001c")
    }
    "of a peripheral derived from a corrected one carries the correction and the reason when expanded" in {
      val a = applied(text)
      val o = opts.copy(patched = Some(a))
      val uart1 = Constants.module(a.device, Constants.selected(a.device, o).find(_.name == "UART1").get, o)
      uart1 should include("// - the datasheet gives PARITY bits 4:2")
      uart1 should include("const CTRL_PARITY_MASK: u32 = 0x0000001c")
    }
    "of a peripheral the patch did not touch is exactly what it was" in {
      val a = applied(text)
      Constants.module(a.device, a.device.byName("WIDE"), opts.copy(patched = Some(a))) shouldBe Constants.module(tiny, tiny.byName("WIDE"), opts)
    }
    "keeps a renamed peripheral's reasons under its new name" in {
      val a = applied(text + "\n{ op = modify-peripheral, peripheral = UART0, set { name = USART0 }, why = renamed }")
      a.whys("USART0") shouldBe Seq("the datasheet gives PARITY bits 4:2", "renamed")
      a.whys.contains("UART0") shouldBe false
    }
  }
