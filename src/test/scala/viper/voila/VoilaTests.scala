/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila

import java.nio.file.Path
import org.scalatest.BeforeAndAfterAll
import viper.silver
import viper.voila.frontend.Config
import viper.voila.reporting.{Failure, Success, VoilaError}
import viper.silver.testing._

class VoilaTests extends AnnotationBasedTestSuite with BeforeAndAfterAll {
  val testDirectories: Seq[String] = Vector("regressions")
  override val defaultTestPattern: String = ".*\\.vl"

  var voilaInstance: Voila = _

  override def beforeAll(): Unit = {
    voilaInstance = new Voila()
  }

  override def afterAll(): Unit = {
    voilaInstance = null
  }

  val voilaInstanceUnderTest: SystemUnderTest =
    new SystemUnderTest with TimingUtils {
      val projectInfo: ProjectInfo = new ProjectInfo(List("Voila"))

      def run(testInput: AnnotatedTestInput): Seq[AbstractOutput] = {
        val config =
          new Config(Array(
            "--logLevel", "ERROR",
            "-i", testInput.file.toFile.getPath))

        val (result, elapsed) = time(() => voilaInstance.verify(config))

        info(s"Time required: $elapsed")

        result match {
          case None =>
            info("Voial failed")
            Vector.empty
          case Some(Success) =>
            Vector.empty
          case Some(Failure(errors)) =>
            errors map VoilaTestOutput
        }
      }
    }

  def systemsUnderTest: Seq[silver.testing.SystemUnderTest] = Vector(voilaInstanceUnderTest)
}

case class VoilaTestOutput(error: VoilaError) extends AbstractOutput {
  def isSameLine(file: Path, lineNr: Int): Boolean = error.position.line == lineNr
  def fullId: String = error.id
}