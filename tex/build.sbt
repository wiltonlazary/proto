lazy val prototex = project.in(file(".")).settings(
  name := "proto-tex"
, version := zero.git.version()
, scalaVersion := "3.0.0-RC1"
, crossScalaVersions := "3.0.0-RC1" :: "2.13.5" :: Nil
, libraryDependencies ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 13)) => Seq(
        "org.scala-lang" % "scala-reflect" % scalaVersion.value
      , "org.scalatest" %% "scalatest" % "3.1.1" % Test
      )
      case _ => Nil
    }
  },
  /* publishing */
  organization := "io.github.zero-deps",
  homepage := Some(url("https://github.com/zero-deps/proto")),
  scmInfo := Some(ScmInfo(url("https://github.com/zero-deps/proto"), "git@github.com:zero-deps/proto.git")),
  developers := List(Developer("Zero", "Deps", "zerodeps.org@gmail.com", url("https://github.com/zero-deps"))),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  publishMavenStyle := true,
  versionScheme := Some("pvp"),
  publishTo := Some(Opts.resolver.sonatypeStaging),
  usePgpKeyHex("F68F0EADDB81EF533C4E8E3228C90422E5A0DB21"),
  /* publishing */
)

lazy val ext = project.in(file("../deps/ext"))

lazy val protoops = project.in(file("../ops")).settings(
  name := "proto-ops",
  version := zero.git.version(),
  scalaVersion := "3.0.0-RC1",
  crossScalaVersions := "3.0.0-RC1" :: "2.13.5" :: Nil,
  resolvers += Resolver.JCenterRepository,
  libraryDependencies ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 13)) => Seq(
        "org.scala-lang" % "scala-reflect" % scalaVersion.value
      )
      case _ => Nil
    }
  },
  /* publishing */
  organization := "io.github.zero-deps",
  homepage := Some(url("https://github.com/zero-deps/proto")),
  scmInfo := Some(ScmInfo(url("https://github.com/zero-deps/proto"), "git@github.com:zero-deps/proto.git")),
  developers := List(Developer("Zero", "Deps", "zerodeps.org@gmail.com", url("https://github.com/zero-deps"))),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  publishMavenStyle := true,
  versionScheme := Some("pvp"),
  publishTo := Some(Opts.resolver.sonatypeStaging),
  usePgpKeyHex("F68F0EADDB81EF533C4E8E3228C90422E5A0DB21"),
  /* publishing */
).dependsOn(protosyntax)

lazy val protosyntax = project.in(file("../syntax")).settings(
  name := "proto-syntax",
  version := zero.git.version(),
  scalaVersion := "3.0.0-RC1",
  crossScalaVersions := "3.0.0-RC1" :: "2.13.5" :: Nil,
  resolvers += Resolver.JCenterRepository,
  /* publishing */
  organization := "io.github.zero-deps",
  homepage := Some(url("https://github.com/zero-deps/proto")),
  scmInfo := Some(ScmInfo(url("https://github.com/zero-deps/proto"), "git@github.com:zero-deps/proto.git")),
  developers := List(Developer("Zero", "Deps", "zerodeps.org@gmail.com", url("https://github.com/zero-deps"))),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  publishMavenStyle := true,
  versionScheme := Some("pvp"),
  publishTo := Some(Opts.resolver.sonatypeStaging),
  usePgpKeyHex("F68F0EADDB81EF533C4E8E3228C90422E5A0DB21"),
  /* publishing */
)

dependsOn(protoops, ext)
