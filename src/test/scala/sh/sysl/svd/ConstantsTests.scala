package sh.sysl.svd

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.xml.XML

class ConstantsTests extends AnyFreeSpec with Matchers:

  private def tiny: Device = Parser.parse(XML.load(getClass.getResourceAsStream("/tiny.svd")))
  private val opts = Constants.Options(moduleRoot = "sh.sysl.tiny")

  "a peripheral names a module in lower case, keeping the datasheet's spelling inside it" in {
    Constants.moduleName("IO_BANK0") shouldBe "io_bank0"
    Constants.moduleName("UART0") shouldBe "uart0"
  }

  "a module lands at its own name twice, which is how the org lays out a sub-module" in {
    Constants.emit(tiny, opts).map(_._1) shouldBe Seq(
      "sh/sysl/tiny/uart0/uart0.sysl",
      "sh/sysl/tiny/uart1/uart1.sysl",
      "sh/sysl/tiny/wide/wide.sysl"
    )
  }

  "the whole of a small peripheral, as emitted" in {
    // A golden output. It is here so that a change to the shape of the generated source is something
    // somebody chose, rather than something that happened.
    Constants.module(tiny, tiny.byName("UART0"), opts) shouldBe
      """// A serial port
        |
        |module sh.sysl.tiny.uart0
        |
        |// The peripheral's base address. Every offset below is from here.
        |const BASE: u32 = 0x40070000
        |
        |// Control
        |const CTRL_OFFSET: u32 = 0x00000000
        |const CTRL_ENABLE_MASK: u32 = 0x00000001
        |const CTRL_ENABLE_SHIFT: u32 = 0
        |const CTRL_PARITY_MASK: u32 = 0x0000000c
        |const CTRL_PARITY_SHIFT: u32 = 2
        |const CTRL_PARITY_NONE: u32 = 0
        |const CTRL_PARITY_EVEN: u32 = 1
        |const CTRL_PARITY_ODD_PARITY: u32 = 2
        |
        |const DATA_OFFSET: u32 = 0x00000004
        |const DATA_BYTE_MASK: u32 = 0x000000ff
        |const DATA_BYTE_SHIFT: u32 = 0
        |""".stripMargin
  }

  "the same SVD emits byte-identical sysl every time" in {
    // The property that makes a regeneration diff worth reading. It is also the one most likely to
    // break without anyone noticing, since map iteration order is stable until it is not.
    val once  = Constants.emit(tiny, opts)
    val again = Constants.emit(Parser.parse(XML.load(getClass.getResourceAsStream("/tiny.svd"))), opts)
    again shouldBe once
  }

  "descriptions" - {
    "are on registers by default, and not on fields" in {
      val text = Constants.module(tiny, tiny.byName("UART0"), opts)
      text should include("// Control")
      text should not include "// Turns the port on"
    }
    "can be turned off entirely" in {
      val text = Constants.module(tiny, tiny.byName("UART0"), opts.copy(descriptions = Constants.Descriptions.None))
      text should not include "// Control"
      text should include("const CTRL_OFFSET")
    }
    "can be put on fields and enumerated values too" in {
      val text = Constants.module(tiny, tiny.byName("UART0"), opts.copy(descriptions = Constants.Descriptions.All))
      text should include("// Turns the port on")
      text should include("// No parity bit")
    }
  }

  "absolute addresses are emitted only when asked for" in {
    Constants.module(tiny, tiny.byName("UART0"), opts) should not include "CTRL_ADDR"
    val text = Constants.module(tiny, tiny.byName("UART0"), opts.copy(absoluteAddresses = true))
    text should include("const DATA_ADDR: u32 = 0x40070004")
  }

  "a derived peripheral" - {
    "writes its parent's registers out again by default" in {
      val text = Constants.module(tiny, tiny.resolved.find(_.name == "UART1").get, opts)
      text should include("const BASE: u32 = 0x40078000")
      text should include("const CTRL_OFFSET: u32 = 0x00000000")
    }
    "points at its parent instead when asked to" in {
      val byBase = opts.copy(derived = Constants.Derived.BaseOnly)
      val text   = Constants.module(tiny, Constants.selected(tiny, byBase).find(_.name == "UART1").get, byBase)
      text should include("const BASE: u32 = 0x40078000")
      text should include("uart0's offsets")
      text should not include "CTRL_OFFSET"
    }
  }

  "the filters" - {
    "keep only what --include names" in {
      Constants.selected(tiny, opts.copy(include = Set("WIDE"))).map(_.name) shouldBe Seq("WIDE")
    }
    "drop what --exclude names" in {
      Constants.selected(tiny, opts.copy(exclude = Set("UART0", "UART1"))).map(_.name) shouldBe Seq("WIDE")
    }
    "let exclude win over include, since a name in both is a contradiction" in {
      Constants.selected(tiny, opts.copy(include = Set("WIDE"), exclude = Set("WIDE"))) shouldBe empty
    }
  }

  "a field that fills its register gets the whole mask" in {
    Constants.module(tiny, tiny.byName("WIDE"), opts) should include("const ALL_EVERY_MASK: u32 = 0xffffffff")
  }

  "an enumerated value's name is upper-cased, alone among the names here" in {
    // A vendor writes these in whatever case it likes -- the RP2350's GPIO functions are `sio_0` and
    // `jtag_tck` -- and a lower-case tail next to CTRL_PARITY_MASK reads as a slip rather than a
    // choice. Everything else keeps the datasheet's own spelling.
    val text = Constants.module(tiny, tiny.byName("UART0"), opts)
    text should include("const CTRL_PARITY_ODD_PARITY: u32 = 2")
    text should not include "odd_parity"
  }

  "the integer type comes from the device's own register width" in {
    Constants.module(tiny, tiny.byName("WIDE"), opts) should include("const BASE: u32")
    val wide = tiny.copy(width = 64)
    Constants.module(wide, wide.byName("WIDE"), opts) should include("const BASE: u64 = 0x0000000050000000")
  }
