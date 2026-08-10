package sh.sysl.svd

import java.io.File
import java.nio.file.{Files, Paths}
import scopt.OParser

/** The command line.
  *
  * Sub-commands rather than one pipeline, because the two emitters answer to different consumers: the
  * constants are compile-time source for a package, and the table is runtime data for a program that
  * looks a register up while it is running. They share the parse and nothing else.
  */
object Main:

  case class Config(
      command: String = "",
      input: File = File("."),
      output: File = File("."),
      moduleRoot: String = "sh.sysl.rp2350",
      descriptions: String = "registers",
      absolute: Boolean = false,
      derived: String = "expand",
      include: Seq[String] = Seq.empty,
      exclude: Seq[String] = Seq.empty,
      dryRun: Boolean = false
  )

  private val builder = OParser.builder[Config]

  private val parser =
    import builder.*
    OParser.sequence(
      programName("svd"),
      head("svd", "0.1.0", "— turns a CMSIS SVD description of a chip into sysl"),
      cmd("constants")
        .action((_, c) => c.copy(command = "constants"))
        .text("emit one sysl module of constants per peripheral")
        .children(
          arg[File]("<file.svd>").action((f, c) => c.copy(input = f)).text("the SVD to read"),
          opt[File]('o', "output")
            .action((f, c) => c.copy(output = f))
            .text("the library root to write modules under; defaults to the working directory"),
          opt[String]('m', "module-root")
            .action((s, c) => c.copy(moduleRoot = s))
            .text("the module every peripheral becomes a sub-module of; defaults to sh.sysl.rp2350"),
          opt[String]("descriptions")
            .validate(s => if Set("none", "registers", "all")(s) then success else failure("--descriptions takes 'none', 'registers' or 'all'"))
            .action((s, c) => c.copy(descriptions = s))
            .text("how much of the SVD's prose becomes comments: 'none', 'registers' (the default), or 'all'"),
          opt[Unit]("absolute")
            .action((_, c) => c.copy(absolute = true))
            .text("emit each register's absolute address as well as its offset"),
          opt[String]("derived")
            .validate(s => if Set("expand", "base-only")(s) then success else failure("--derived takes 'expand' or 'base-only'"))
            .action((s, c) => c.copy(derived = s))
            .text("what to do with a peripheral the SVD derives from another: 'expand' (the default) writes its registers out again, 'base-only' emits its base address and points at the parent"),
          opt[Seq[String]]("include")
            .action((s, c) => c.copy(include = s))
            .text("emit only these peripherals, by SVD name"),
          opt[Seq[String]]("exclude")
            .action((s, c) => c.copy(exclude = s))
            .text("emit everything but these peripherals, by SVD name"),
          opt[Unit]("dry-run")
            .action((_, c) => c.copy(dryRun = true))
            .text("report what would be written, and write nothing")
        ),
      cmd("survey")
        .action((_, c) => c.copy(command = "survey"))
        .text("report what is in an SVD without emitting anything")
        .children(arg[File]("<file.svd>").action((f, c) => c.copy(input = f)).text("the SVD to read")),
      checkConfig(c => if c.command.isEmpty then failure("give a command: 'constants' or 'survey'") else success)
    )

  def main(args: Array[String]): Unit =
    OParser.parse(parser, args, Config()) match
      case None         => sys.exit(2)
      case Some(config) =>
        try
          val device = Parser.parse(config.input)
          config.command match
            case "survey"    => survey(device)
            case "constants" => constants(device, config)
            case other       => throw SvdError(s"no such command as '$other'")
        catch
          case e: SvdError =>
            Console.err.println(s"error: ${e.message}")
            sys.exit(1)

  private def survey(device: Device): Unit =
    val periphs = device.resolved
    val registers = periphs.map(_.registers.size).sum
    val fields = periphs.map(_.registers.map(_.fields.size).sum).sum
    val enums = periphs.map(_.registers.map(_.fields.map(_.enumeratedValues.size).sum).sum).sum
    println(s"device            ${device.name}")
    println(s"width             ${device.width}")
    println(f"peripherals       ${periphs.size}%,d  (${device.peripherals.count(_.derivedFrom.isDefined)}%,d derived)")
    println(f"registers         $registers%,d")
    println(f"fields            $fields%,d")
    println(f"enumerated values $enums%,d")
    println(f"constants emitted ${periphs.size + registers + 2 * fields + enums}%,d")
    println()
    println("largest peripherals, by field count:")
    for p <- periphs.sortBy(-_.registers.map(_.fields.size).sum).take(10) do
      val r = p.registers.size
      val f = p.registers.map(_.fields.size).sum
      println(f"  ${p.name}%-18s $r%5d registers  $f%6d fields")

  private def constants(device: Device, config: Config): Unit =
    val opts = Constants.Options(
      moduleRoot = config.moduleRoot,
      descriptions = config.descriptions match
        case "none"      => Constants.Descriptions.None
        case "all"       => Constants.Descriptions.All
        case _           => Constants.Descriptions.Registers,
      absoluteAddresses = config.absolute,
      derived = if config.derived == "base-only" then Constants.Derived.BaseOnly else Constants.Derived.Expand,
      include = config.include.toSet,
      exclude = config.exclude.toSet
    )

    for name <- (opts.include ++ opts.exclude) if !device.byName.contains(name) do
      throw SvdError(s"'$name' is not a peripheral of ${device.name}; 'svd survey' lists them")

    val files = Constants.emit(device, opts)
    if files.isEmpty then throw SvdError("the filters selected no peripherals, so there is nothing to write")

    for (path, text) <- files do
      val target = Paths.get(config.output.getPath, path)
      if config.dryRun then println(f"${text.linesIterator.size}%6d lines  $target")
      else
        Files.createDirectories(target.getParent)
        Files.writeString(target, text)

    val lines = files.map(_._2.linesIterator.size).sum
    val verb  = if config.dryRun then "would write" else "wrote"
    println(f"$verb ${files.size}%,d modules, ${lines}%,d lines, under ${config.output.getPath}")
