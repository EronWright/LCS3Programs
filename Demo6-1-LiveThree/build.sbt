name := "Demo6-1-LiveThree"

version := "1.0"

scalaVersion := "2.10.6"

libraryDependencies ++= Seq(
  "io.reactivex" % "rxscala_2.10" % "0.25.1" force(),
  "io.reactivex" % "rxjava-debug" % "1.0.3"
)

mainClass := Some("livethree.Main")