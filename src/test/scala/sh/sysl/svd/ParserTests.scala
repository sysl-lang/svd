package sh.sysl.svd

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.xml.XML

class ParserTests extends AnyFreeSpec with Matchers:

  private def tiny: Device = Parser.parse(XML.load(getClass.getResourceAsStream("/tiny.svd")))

  "a number" - {
    "is decimal by default" in { Parser.number("42") shouldBe 42L }
    "is hex after 0x" in { Parser.number("0x40070000") shouldBe 0x40070000L }
    "is hex after 0X" in { Parser.number("0X1F") shouldBe 31L }
    "is binary after #" in { Parser.number("#1011") shouldBe 11L }
    "takes a leading +" in { Parser.number("+7") shouldBe 7L }
    "scales by a k, m, g or t suffix" in {
      Parser.number("4k") shouldBe 4096L
      Parser.number("1M") shouldBe 1024L * 1024
      Parser.number("2g") shouldBe 2L * 1024 * 1024 * 1024
    }
    "reaches the top of a 32-bit register" in { Parser.number("0xffffffff") shouldBe 0xffffffffL }
    "is refused when it is not one" in {
      (the[SvdError] thrownBy Parser.number("nonsense")).message shouldBe "'nonsense' is not a number SVD can express"
    }
  }

  "the three spellings of a field's position mean the same thing" - {
    val d = tiny
    def field(peripheral: String, register: String, name: String): Field =
      d.byName(peripheral).registers.find(_.name == register).get.fields.find(_.name == name).get

    "bitRange [msb:lsb]" in {
      val f = field("UART0", "CTRL", "PARITY")
      f.bitOffset shouldBe 2
      f.bitWidth shouldBe 2
      f.mask shouldBe 0xcL
    }
    "bitOffset with bitWidth" in {
      val f = field("UART0", "DATA", "BYTE")
      f.bitOffset shouldBe 0
      f.bitWidth shouldBe 8
      f.mask shouldBe 0xffL
    }
    "lsb with msb" in {
      val f = field("WIDE", "ALL", "EVERY")
      f.bitOffset shouldBe 0
      f.bitWidth shouldBe 32
      f.mask shouldBe 0xffffffffL
    }
  }

  "a mask sits where the field does" in {
    Field("F", None, bitOffset = 8, bitWidth = 4, None, Nil).mask shouldBe 0xf00L
    Field("F", None, bitOffset = 31, bitWidth = 1, None, Nil).mask shouldBe 0x80000000L
    Field("F", None, bitOffset = 0, bitWidth = 32, None, Nil).mask shouldBe 0xffffffffL
  }

  "a derived peripheral" - {
    "keeps its own name, address and description" in {
      val u1 = tiny.resolved.find(_.name == "UART1").get
      u1.baseAddress shouldBe 0x40078000L
      u1.description shouldBe Some("A second serial port")
    }
    "takes the registers of the one it derives from" in {
      val d = tiny
      val u0 = d.resolved.find(_.name == "UART0").get
      val u1 = d.resolved.find(_.name == "UART1").get
      u1.registers shouldBe u0.registers
      u1.registers.map(_.name) shouldBe Seq("CTRL", "DATA")
    }
    "is refused when what it derives from is not there" in {
      val xml = <device><name>D</name><peripherals>
          <peripheral derivedFrom="ABSENT"><name>P</name><baseAddress>0</baseAddress></peripheral>
        </peripherals></device>
      (the[SvdError] thrownBy Parser.parse(xml)).message shouldBe
        "peripheral 'P' derives from 'ABSENT', which this file does not declare"
    }
  }

  "prose is folded onto one line, because the file indents it" in {
    tiny.description shouldBe Some("A chip that does not exist, holding one of everything this tool understands.")
  }

  "enumerated values are read, with their prose" in {
    val parity = tiny.byName("UART0").registers.head.fields.find(_.name == "PARITY").get
    parity.enumeratedValues.map(e => (e.name, e.value)) shouldBe Seq(("NONE", 0L), ("EVEN", 1L))
    parity.enumeratedValues.head.description shouldBe Some("No parity bit")
  }

  "what the tool does not understand, it refuses rather than dropping" - {
    // The point of each of these is that the alternative -- ignoring the element -- produces a
    // register map that is quietly missing registers and still compiles.
    "a <dim> array" in {
      val xml = <device><name>D</name><peripherals><peripheral><name>P</name><baseAddress>0</baseAddress>
          <registers><register><dim>4</dim><name>R</name><addressOffset>0</addressOffset></register></registers>
        </peripheral></peripherals></device>
      (the[SvdError] thrownBy Parser.parse(xml)).message shouldBe
        "register 'P.R' uses <dim> (a register or field array), which this tool does not understand — it would be dropped silently, so the run stops instead"
    }
    "a cluster" in {
      val xml = <device><name>D</name><peripherals><peripheral><name>P</name><baseAddress>0</baseAddress>
          <registers><cluster><name>C</name></cluster></registers></peripheral></peripherals></device>
      (the[SvdError] thrownBy Parser.parse(xml)).message shouldBe
        "peripheral 'P' holds a <cluster>, which this tool does not understand"
    }
    "an alternate group" in {
      val xml = <device><name>D</name><peripherals><peripheral><name>P</name><baseAddress>0</baseAddress>
          <registers><register><name>R</name><addressOffset>0</addressOffset>
            <alternateGroup>G</alternateGroup></register></registers></peripheral></peripherals></device>
      (the[SvdError] thrownBy Parser.parse(xml)).message shouldBe
        "register 'P.R' uses <alternateGroup> (an alternate register group), which this tool does not understand — it would be dropped silently, so the run stops instead"
    }
    "a field that says nothing about which bits it occupies" in {
      val xml = <device><name>D</name><peripherals><peripheral><name>P</name><baseAddress>0</baseAddress>
          <registers><register><name>R</name><addressOffset>0</addressOffset>
            <fields><field><name>F</name></field></fields></register></registers></peripheral></peripherals></device>
      (the[SvdError] thrownBy Parser.parse(xml)).message shouldBe
        "field 'R.F' says nothing about which bits it occupies"
    }
    "a bitRange whose msb is below its lsb" in {
      val xml = <device><name>D</name><peripherals><peripheral><name>P</name><baseAddress>0</baseAddress>
          <registers><register><name>R</name><addressOffset>0</addressOffset>
            <fields><field><name>F</name><bitRange>[2:7]</bitRange></field></fields>
          </register></registers></peripheral></peripherals></device>
      (the[SvdError] thrownBy Parser.parse(xml)).message shouldBe
        "field 'R.F' has bitRange '[2:7]', whose msb is below its lsb"
    }
  }

  "a document that is not an SVD is refused for being one" in {
    (the[SvdError] thrownBy Parser.parse(<html><body/></html>)).message shouldBe
      "the document's root is <html> rather than <device>, so it is not an SVD file"
  }

  "a device with no peripherals is refused" in {
    (the[SvdError] thrownBy Parser.parse(<device><name>D</name><peripherals/></device>)).message shouldBe
      "the file declares no peripherals"
  }

  "a register with no address offset is refused, naming it" in {
    val xml = <device><name>D</name><peripherals><peripheral><name>P</name><baseAddress>0</baseAddress>
        <registers><register><name>R</name></register></registers></peripheral></peripherals></device>
    (the[SvdError] thrownBy Parser.parse(xml)).message shouldBe "register 'P.R' has no <addressOffset>"
  }
