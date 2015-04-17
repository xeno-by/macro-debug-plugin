import java.io._
import java.security.Permission

import com.sun.org.apache.bcel.internal.classfile._
import com.sun.org.apache.bcel.internal.util.{ClassPath, SyntheticRepository}
import macros.Macros
import org.scalatest.FunSuite
import org.scalatest._


class ExpansionsSuite extends FunSuite with Matchers {

  //source :
  def virtualizedOpen(body: => Unit): (Int, String) = {
    val outputStorage = new ByteArrayOutputStream()
    val outputStream = new PrintStream(outputStorage)
    case class SystemExitException(exitCode: Int) extends SecurityException
    val manager = System.getSecurityManager
    System.setSecurityManager(new SecurityManager {
      override def checkPermission(permission: Permission): Unit = ()

      override def checkPermission(permission: Permission, context: AnyRef): Unit = ()

      override def checkExit(exitCode: Int): Unit = throw new SystemExitException(exitCode)
    })
    try {
      scala.Console.withOut(outputStream)(scala.Console.withErr(outputStream)(body)); throw new Exception("failed to capture exit code")
    }
    catch {
      case SystemExitException(exitCode) => outputStream.close(); (exitCode, outputStorage.toString)
    }
    finally System.setSecurityManager(manager)
  }

  def runExpansionTest(testDir: File): File = {
    // val sources = testDir.listFiles().filter(_.getName.endsWith(".scala")).map(_.getAbsolutePath).toList
    val sources = List(testDir.getAbsolutePath)
    val cp = List("-cp", sys.props("sbt.paths.tests.classpath"))
    val debugPlugin = List("-Xplugin:" + sys.props("sbt.paths.plugin.jar"), "-Xplugin-require:macro-debug")
    val tempDir = File.createTempFile("temp", System.nanoTime.toString)
    tempDir.delete()
    tempDir.mkdir()
    val output = List("-d", tempDir.getAbsolutePath)
    val options = cp ++ debugPlugin ++ output ++ sources
    val (exitCode, stdout) = virtualizedOpen(scala.tools.nsc.Main.main(options.toArray))
    //println("The output of the compiler is:\n" + stdout)
    if (exitCode != 0) fail("The compiler has exited with code " + exitCode + ":\n" + stdout)
    tempDir
  }

  val resourceDir = new File(System.getProperty("sbt.paths.tests.macros") + File.separatorChar + "resources")
  val testDirs = resourceDir.listFiles().filter(_.listFiles().nonEmpty).filter(!_.getName.endsWith("_disabled"))


  def openRunOutput(pack: String, claz: String, clazOut: String): JavaClass = {
    val sourceImpl = testDirs.filter(_.getName == pack).head.listFiles().filter(_.getName == claz).head
    val out = runExpansionTest(sourceImpl).listFiles().filter(_.getName == "macros").head.listFiles().filter(_.getName == "resources")
      .head.listFiles().filter(_.getName == pack).head.listFiles()

    new ClassParser(new FileInputStream(out.filter(_.getName == clazOut)
      .head), out.filter(_.getName == clazOut).head.getName).parse()
  }

  def deleteAllTemps(): Unit = {

  }

  test("helloworld/Test") {
    val jc1 = openRunOutput("helloworld", "Test.scala", "Test$.class")
    val methFoo: Method = jc1.getMethods.filter(m => m.getName == "foo")(0)
    val lineNumberTable = methFoo.getLineNumberTable

    lineNumberTable.toString should fullyMatch regex """LineNumber\(.+, 8\), LineNumber\(.+, 12\)"""
  }

  test("helloworld/MultipleExtensionsInFile") {
    val jc1 = openRunOutput("helloworld", "MultipleExpansionsInFile.scala", "MultipleExpansionsInFile$.class")
    val methFoo = jc1.getMethods.filter(m => m.getName == "foo")(0)
    val lineNumberTable = methFoo.getLineNumberTable //
    lineNumberTable.toString.replaceAll("\n", "") should fullyMatch regex
      """LineNumber\(.+, 7\), LineNumber\(.+, 17\), LineNumber\(.+, 8\), LineNumber\(.+, 19\), LineNumber\(.+, 9\), LineNumber\(.+, 21\)"""
  }

  test("helloworld/MultipleExpansionsInLine") {
    val jc1 = openRunOutput("helloworld", "MultipleExpansionsInLine.scala", "MultipleExpansionsInLine$.class")
    val methods = jc1.getMethods
    val methFoo = methods.filter(m => m.getName == "fooOne")(0)
    val lineNumberTable = methFoo.getLineNumberTable
    lineNumberTable.toString.replaceAll("\n", "") should fullyMatch regex
      """LineNumber\(.+, 7\), LineNumber\(.+, 20\), LineNumber\(.+, 7\), LineNumber\(.+, 22\), LineNumber\(.+, 7\), LineNumber\(.+, 24\), LineNumber\(.+, 7\)"""
    val methBar1 = methods.filter(m => m.getName == "barOne")(0)
    methBar1.getLineNumberTable.toString.replaceAll("\n", "") should fullyMatch regex
      """LineNumber\(.+, 11\), LineNumber\(.+, 26\)""" //This expansion has 8 lines
    val methBar2 = methods.filter(m => m.getName == "barTwo")(0)
    methBar2.getLineNumberTable.toString.replaceAll("\n", "") should fullyMatch regex
      """LineNumber\(.+, 15\), LineNumber\(.+, 34\), LineNumber\(.+, 15\), LineNumber\(.+, 42\), LineNumber\(.+, 15\), LineNumber\(.+, 50\), LineNumber\(.+, 15\)"""
  }

  test("helloworld/MultipleExpansionsInFile2") {
    val jc1 = openRunOutput("helloworld", "MultipleExpansionsInFile2.scala", "MultipleExpansionsInFile2$.class")
    val methods = jc1.getMethods

    val methFoo = jc1.getMethods.filter(m => m.getName == "foo")(0)
    val lineNumberTable = methFoo.getLineNumberTable //
    lineNumberTable.toString.replaceAll("\n", "") should fullyMatch regex
      """LineNumber\(.+, 8\), LineNumber\(.+, 24\), LineNumber\(.+, 9\), LineNumber\(.+, 26\), LineNumber\(.+, 10\), LineNumber\(.+, 28\)"""

    val methFooBar = methods.filter(m => m.getName == "foobar")(0)
    methFooBar.getLineNumberTable.toString.replaceAll("\n", "") should fullyMatch regex
      """LineNumber\(.+, 14\), LineNumber\(.+, 15\), LineNumber\(.+, 16\), LineNumber\(.+, 46\), LineNumber\(.+, 16\)"""
    //ln 30, 38 are for the expansions of val w1, w2

  }

  test("helloworld/RecursionExpansion") {
    val jc1 = openRunOutput("helloworld", "RecursionExpansion.scala", "RecursionExpansion$.class")
    val methRecursion = jc1.getMethods.filter(m => m.getName == "foo")(0)
    methRecursion.getLineNumberTable.toString.replaceAll("\n", "") should fullyMatch regex
      """LineNumber\(.+, 7\), LineNumber\(.+, 11\), LineNumber\(.+, 7\)"""
  }

  test("helloworld/SingleExpansion") {
    val jc1 = openRunOutput("helloworld", "SingleExpansion.scala", "SingleExpansion$.class")
    val methRecursive = jc1.getMethods.filter(m => m.getName == "foo")(0)
    methRecursive.getLineNumberTable.toString.replaceAll("\n", "") should fullyMatch regex
      """LineNumber\(.+, 10\), LineNumber\(.+, 18\), LineNumber\(.+, 10\)"""
  }

  test("helloworld/ComboExpansions") {
    val jc1 = openRunOutput("helloworld", "ComboExpansions.scala", "ComboExpansions$.class")
    val methFoo = jc1.getMethods.filter(_.getName == "foo").head
    methFoo.getLineNumberTable.toString.replaceAll("\n", "") should fullyMatch regex
      """LineNumber\(.+, 11\), LineNumber\(.+, 12\), LineNumber\(.+, 16\), LineNumber\(.+, 17\), LineNumber\(.+, 19\), LineNumber\(.+, 23\), LineNumber\(.+, 40\), LineNumber\(.+, 23\), LineNumber\(.+, 24\)"""
  }


}

