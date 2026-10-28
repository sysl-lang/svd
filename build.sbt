ThisBuild / organization := "sh.sysl"
ThisBuild / version      := "0.1.1"
ThisBuild / scalaVersion := "3.8.4"

lazy val svd = (project in file("."))
  .settings(
    name        := "svd",
    description := "Turns a CMSIS SVD description of a chip into sysl",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      "-Werror"
    ),
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-xml"  % "2.4.0",
      "com.github.scopt"       %% "scopt"      % "4.1.0",
      "org.scalatest"          %% "scalatest"  % "3.2.19" % Test
    )
  )
