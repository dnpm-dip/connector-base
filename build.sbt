


name := "connector-base"
ThisBuild / organization := "de.dnpm.dip"
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / version      := "1.0-SNAPSHOT"


//-----------------------------------------------------------------------------
// PROJECT
//-----------------------------------------------------------------------------

lazy val root = project.in(file("."))
  .settings(settings)
  .settings(
    libraryDependencies ++= Seq(
    "org.scalatest"          %% "scalatest"               % "3.2.17" % Test,
    "de.dnpm.dip"            %% "service-base"            % "1.0-SNAPSHOT",
    "com.typesafe.play"      %% "play-ahc-ws-standalone"  % "2.1.3",
    "com.typesafe.play"      %% "play-ws-standalone-json" % "2.1.3",
    "org.scala-lang.modules" %% "scala-xml"               % "2.2.0",
    "org.typelevel"          %% "cats-effect"             % "3.5.1"
   )
 )


//-----------------------------------------------------------------------------
// SETTINGS
//-----------------------------------------------------------------------------

lazy val settings = commonSettings

lazy val compilerOptions = Seq(
  "-encoding", "utf8",
  "-unchecked",
  "-Xfatal-warnings",
  "-feature",
  "-language:higherKinds",
  "-language:postfixOps",
  "-deprecation"
)

lazy val commonSettings = Seq(
  scalacOptions ++= compilerOptions,
  resolvers ++=
    Seq("Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository") ++
    Resolver.sonatypeOssRepos("releases") ++
    Resolver.sonatypeOssRepos("snapshots")
)
