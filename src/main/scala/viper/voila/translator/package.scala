/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila

import viper.silver.{ast => vpr}
import viper.voila.frontend.{DefaultPrettyPrinter, PAstNode, PrettyPrinter}

package object translator {
  implicit class RichViperNode[N <: vpr.Node](node: N) {
    def withSource(source: PAstNode): N = {
      val (pos, info, errT) = node.getPrettyMetadata

      require(info == vpr.NoInfo)
      val newInfo = SourceInfo(source)

      node.duplicateMeta((pos, newInfo, errT)).asInstanceOf[N]
    }
  }

  case class SourceInfo(source: PAstNode) extends vpr.Info {
    def comment: Seq[String] = Vector.empty
  }

  implicit val prettyPrinter: PrettyPrinter = new DefaultPrettyPrinter
}