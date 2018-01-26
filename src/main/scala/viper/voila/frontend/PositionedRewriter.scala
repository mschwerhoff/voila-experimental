/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila.frontend

import scala.collection.breakOut
import org.bitbucket.inkytonik.kiama.relation.TreeRelation.isLeaf
import org.bitbucket.inkytonik.kiama.util.Positions

class PositionedRewriter(override val positions: Positions)
    extends org.bitbucket.inkytonik.kiama.rewriting.PositionedRewriter
       with org.bitbucket.inkytonik.kiama.rewriting.Cloner {

  def deepcloneAndRename[T <: Product](t: T, renamings: Map[String, String]): T = {
    /* Implementation adapted from Cloner.deepclone */

    val cloner =
      everywherebu(rule[PAstNode] {
        case id @ PIdnDef(_) if renamings.contains(id.name) => dup(id, Array(renamings(id.name)))
        case id @ PIdnUse(_) if renamings.contains(id.name) => dup(id, Array(renamings(id.name)))
        case n if isLeaf(n) => copy(n)
      })

    rewrite(cloner)(t)
  }

  def deepcloneAndReplace[N <: PAstNode]
                         (root: N,
                          fullDeclarationReplacements: Map[PDeclaration, PDeclaration] = Map.empty,
                          useReplacements: Map[PIdnDef, PAstNode] = Map.empty,
                          generalReplacements: Map[PAstNode, PAstNode] = Map.empty)
                         : N = {

    /* Implementation adapted from Cloner.deepclone */

    val fullDeclarationRenamings: Map[String, String] =
      fullDeclarationReplacements.map { case (from, to) => from.id.name -> to.id.name }

    val useRenamings: Map[String, PAstNode] =
      useReplacements.map { case (from, to) => from.name -> to }

    val cloner =
      alltd(rule[PAstNode] {
        case declaration: PDeclaration if fullDeclarationReplacements.contains(declaration) =>
          copy(fullDeclarationReplacements(declaration))
        case id @ PIdnUse(name) if fullDeclarationRenamings.contains(name) =>
          dup(id, Array(fullDeclarationRenamings(name)))

        case id: PIdnDef if useReplacements.contains(id) =>
          sys.error(
            s"Did not expect to find the definition of ${id.name}, "+
            s"only usages are expected (${id.position})")
        case PIdnExp(PIdnUse(name)) if useRenamings.contains(name) =>
          copy(useRenamings(name))
        case oldIdn @ PIdnUse(name) if useRenamings.contains(name) =>
          val replacement =
            useRenamings(name) match {
              case newIdn: PIdnUse => newIdn
              case PIdnExp(newIdn) => newIdn
              case other =>
                sys.error(
                  s"Unexpectedly found $other " +
                  s"(${other.getClass.getSimpleName}, ${other.position}) as a replacement for " +
                  s"$oldIdn (${oldIdn.getClass.getSimpleName}, ${oldIdn.position})")
            }

          copy(replacement)

        case node if generalReplacements.contains(node) =>
          copy(generalReplacements(node))

        case n if isLeaf(n) =>
          copy(n)
      })

    rewrite(cloner)(root)
  }

  def duplicateAndGenerateRenamings[D <: PDeclaration]
                                   (declarations: Vector[D],
                                    rename: String => String = Predef.identity)
                                   : (Vector[D], Map[D, D]) = {

    var replacements = Map.empty[D, D]

    val renamedDeclarations =
      declarations.map(oldDeclaration => {
        val oldName = oldDeclaration.id.name
        val newName = AstUtils.uniqueName(s"${rename(oldName)}")

        val newDeclaration =
          deepcloneAndRename(oldDeclaration, Map(oldDeclaration.id.name -> newName))

        replacements += oldDeclaration -> newDeclaration

        newDeclaration
      })

    (renamedDeclarations, replacements)
  }

  /* TODO: Consider moving macro expansion code elsewhere, e.g. in a dedicated trait */

  def expand(macros: Vector[PMacro], members: Vector[PMember]): Vector[PMember] = {
    val mm: Map[String, PMacro] = macros.map(makro => makro.id.name -> makro)(breakOut)

    def instantiateMacroBody(formals: Vector[PIdnDef],
                             actuals: Vector[PExpression],
                             localVariableReplacements: Map[PLocalVariableDecl, PLocalVariableDecl],
                             body: PAstNode,
                             appliedMacro: String,
                             applicationPosition: viper.silver.ast.SourcePosition)
                            : PAstNode = {

      /* TODO: Represent formals and actuals more efficiently, e.g. as maps.
       *       Or integrate into replacements map? Length check formals vs actuals would in this
       *       case have to be done at call site, however.
       */

      assert(formals.lengthCompare(actuals.length) == 0,
        s"Number of arguments don't match: macro $appliedMacro expects " +
        s"${formals.length} arguments, but got ${actuals.length} ($applicationPosition)")

      val binders = AstUtils.extractNamedBinders(body)

      val (_, binderReplacements) =
        duplicateAndGenerateRenamings(binders, name => s"${name}_$appliedMacro")

      val declarationReplacements: Map[PDeclaration, PDeclaration] =
        localVariableReplacements ++ binderReplacements

      val formalReplacements: Map[PIdnDef, PExpression] =
        formals.zip(actuals)(breakOut)

      deepcloneAndReplace(
        root = body,
        fullDeclarationReplacements = declarationReplacements,
        useReplacements = formalReplacements)
    }

    def expandMacros(in: PMember): PMember = {
      var additionalLocals = Vector.empty[PLocalVariableDecl]

      /* Traverse the AST (`in`) bottom-up and expand each macro application that is found,
       * while simultaneously renaming - and, in the case of local variables - aggregating
       * variables that are declared inside macro bodies. Such variables are renamed to avoid
       * name clashes, and in the case of local variable declarations (e.g. `int v`) also
       * pulled up to the beginning of the surrounding method.
       */

      val expander =
        everywherebu(rule[PAstNode] {
          case exp @ PIdnExp(PIdnUse(name)) if mm.contains(name) =>
            /* Found a macro application without arguments, e.g. LEN */

            val makro = mm(name).asInstanceOf[PExpressionMacro]

            instantiateMacroBody(
              Vector.empty, Vector.empty, Map.empty, makro.body, makro.id.name, exp.position)

          case call @ PProcedureCall(PIdnUse(name), arguments, Seq()) if mm.contains(name) =>
            /* Found a call to a statement macro, e.g. SWAP(x, y) */

            val makro = mm(name).asInstanceOf[PStatementMacro]
            val formals = makro.formalArguments

            val (renamedDeclarations, localReplacements) =
              duplicateAndGenerateRenamings(makro.locals, name => s"${name}_${makro.id.name}")

            additionalLocals ++= renamedDeclarations

            instantiateMacroBody(
              formals, arguments, localReplacements, makro.body, makro.id.name, call.position)

          case exp @ PPredicateExp(PIdnUse(name), arguments) if mm.contains(name) =>
            /* Found an application of an expression macro, e.g. MAX(x, y) */

            val makro = mm(name).asInstanceOf[PExpressionMacro]
            val formals = makro.formalArguments.getOrElse(Vector.empty)

            instantiateMacroBody(
              formals, arguments, Map.empty, makro.body, makro.id.name, exp.position)

          case proc @ PProcedure(name, ins, outs, inters, pres, posts, locals, body, atomicity) =>
            /* Reached a procedure declaration. Add new local variables introduced by expanding
             * macros to the procedure's list of local variables
             */

            val newChildren: Array[AnyRef] =
              Array(name, ins, outs, inters, pres, posts, locals ++ additionalLocals, body, atomicity)

            dup(proc, newChildren)
        })

      rewrite(expander)(in)
    }

    members map expandMacros
  }
}