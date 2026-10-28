package sh.sysl.svd

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import java.io.File
import java.nio.file.{Files, Path}
import scala.sys.process.*
import scala.util.Try

/** The checks that need something this repository does not carry.
  *
  * Two of them: a real vendor SVD, and the sysl compiler. Both are *cancelled* rather than failed
  * when absent, so a clone with neither still runs a green suite — but where they are present these
  * are the most valuable tests here, because everything in `ConstantsTests` checks the emitter against
  * this tool's own idea of what sysl looks like. Only the compiler can say whether that idea is right.
  *
  * The SVD is the one in the pico-sdk clone that `sysl-lang/pico-scratch` depends on. It is 5.3 MB and
  * deliberately not vendored here; `SVD_FILE` overrides the path.
  */
class RealSvdTests extends AnyFreeSpec with Matchers:

  private val svdPath = sys.env.getOrElse(
    "SVD_FILE",
    s"${sys.props("user.home")}/dev/sysl-lang/pico-scratch/pico-sdk/src/rp2350/hardware_regs/RP2350.svd"
  )

  private lazy val svd = File(svdPath)
  private lazy val syslOnPath = Try(Seq("sysl", "--version").!!).isSuccess

  private def withRealSvd(test: Device => Unit): Unit =
    assume(svd.exists, s"no SVD at $svdPath")
    test(Parser.parse(svd))

  "the RP2350's own SVD" - {
    "parses with nothing refused" in withRealSvd { device =>
      device.name shouldBe "RP2350"
      device.width shouldBe 32
      device.peripherals.size shouldBe 54
      device.peripherals.count(_.derivedFrom.isDefined) shouldBe 9
    }

    "holds the register count the datasheet's peripherals add up to" in withRealSvd { device =>
      val periphs = device.resolved
      periphs.map(_.registers.size).sum shouldBe 3033
      periphs.map(_.registers.map(_.fields.size).sum).sum shouldBe 12209
    }

    "puts IO_BANK0 at the address the datasheet gives" in withRealSvd { device =>
      // Spot-checked against the RP2350 datasheet rather than against this tool's own output.
      device.byName("IO_BANK0").baseAddress shouldBe 0x40028000L
      device.byName("SIO").baseAddress shouldBe 0xd0000000L
      device.byName("TIMER0").baseAddress shouldBe 0x400b0000L
    }

    "gives PIO1 PIO0's registers at PIO1's address" in withRealSvd { device =>
      val resolved = device.resolved
      val pio0 = resolved.find(_.name == "PIO0").get
      val pio1 = resolved.find(_.name == "PIO1").get
      // The RP2350 has three PIOs, a megabyte apart -- not the RP2040's two.
      pio0.baseAddress shouldBe 0x50200000L
      pio1.baseAddress shouldBe 0x50300000L
      resolved.find(_.name == "PIO2").get.baseAddress shouldBe 0x50400000L
      pio1.registers.map(_.name) shouldBe pio0.registers.map(_.name)
    }

    "emits the whole chip" in withRealSvd { device =>
      val files = Constants.emit(device, Constants.Options())
      files.size shouldBe 54
      files.map(_._1) should contain("sh/sysl/rp2350/io_bank0/io_bank0.sysl")
    }
  }

  "what this tool emits is sysl the compiler accepts" - {
    // The claim the whole tool rests on, and nothing else here can make it: every other test compares
    // the output against this file's own expectations.
    "one peripheral compiles as a library" in withRealSvd { device =>
      assume(syslOnPath, "sysl is not on PATH")
      val root = Files.createTempDirectory("svd-test")
      try {
        write(root, Constants.emit(device, Constants.Options(include = Set("TIMER0"))))
        // build-lib checks a library and writes nothing a caller names, so its exit status is the result.
        Seq("sysl", "build-lib", root.toString).! shouldBe 0
      } finally delete(root)
    }

    "the constants can be used for the masking a driver does" in withRealSvd { device =>
      assume(syslOnPath, "sysl is not on PATH")
      val root = Files.createTempDirectory("svd-test")
      // The program lives outside the library root on purpose: everything under a root is compiled
      // *into* the library, so a file with no module header there is refused rather than run.
      val program = Files.createTempDirectory("svd-test-program")
      try {
        write(root, Constants.emit(device, Constants.Options(include = Set("TIMER0"))))
        Files.writeString(
          Path.of(program.toString, "main.sysl"),
          """import sh.sysl.rp2350.timer0
            |
            |val raw: u32 = 0x0000ff00
            |print((raw & timer0.ALARM0_ALARM0_MASK) >> timer0.ALARM0_ALARM0_SHIFT)
            |print(timer0.BASE + timer0.ALARM0_OFFSET)
            |""".stripMargin
        )
        // Named as a file, so it runs without a manifest of its own.
        val output = Seq("sysl", "run", "--lib", root.toString, Path.of(program.toString, "main.sysl").toString).!!.trim
        // 0xff00 is not shifted, and TIMER0 sits at 0x400b0000 with ALARM0 sixteen bytes in.
        output.linesIterator.toSeq shouldBe Seq("65280", (0x400b0000L + 0x10).toString)
      } finally { delete(root); delete(program) }
    }
  }

  private def write(root: Path, files: Seq[(String, String)]): Unit =
    for (path, text) <- files do
      val target = Path.of(root.toString, path)
      Files.createDirectories(target.getParent)
      Files.writeString(target, text)

  private def delete(root: Path): Unit =
    Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
