/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila

import viper.silver
import viper.voila.frontend.{DefaultPrettyPrinter, PAstNode, PrettyPrinter}
import viper.voila.reporting.VerificationError
import viper.silver.{ast => vpr}

package object translator {
  type ErrorTransformer = PartialFunction[silver.verifier.VerificationError, VerificationError]
  type ReasonTransformer = PartialFunction[silver.verifier.ErrorReason, VerificationError]

  implicit class RichViperNode[N <: vpr.Node](node: N) {
    def withSource(source: Option[PAstNode]): N = {
      source match {
        case Some(sourceNode) => this.withSource(sourceNode)
        case None => node
      }
    }

    def withSource(source: PAstNode, overwrite: Boolean = false): N = {
      val (pos, info, errT) = node.getPrettyMetadata

      def message(fieldName: String) = {
        s"Node to annotate ('$node' of class ${node.getClass.getSimpleName}) already has " +
            s"field '$fieldName' set"
      }

      if (!overwrite) {
        require(info == vpr.NoInfo, message("info"))
        require(pos == vpr.NoPosition, message("pos"))
      }

      val newInfo = SourceInfo(source)
      val newPos = vpr.TranslatedPosition(source.position)

      node.withMeta(newPos, newInfo, errT)
    }
  }

  case class SourceInfo(source: PAstNode) extends vpr.Info {
    def comment: Seq[String] = Vector.empty
    lazy val isCached = false
  }

  implicit val prettyPrinter: PrettyPrinter = new DefaultPrettyPrinter

  implicit class RichErrorMessage(error: silver.verifier.ErrorMessage) {
    def causedBy(node: vpr.Node with vpr.Positioned): Boolean =
      node == error.offendingNode && node.pos == error.offendingNode.pos
  }
}
