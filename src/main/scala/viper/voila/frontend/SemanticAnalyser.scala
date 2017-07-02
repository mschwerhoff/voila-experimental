/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila.frontend

import org.bitbucket.inkytonik.kiama.==>
import org.bitbucket.inkytonik.kiama.attribution.{Attribution, Decorators}
import org.bitbucket.inkytonik.kiama.rewriting.Rewriter.{id => _, _}
import org.bitbucket.inkytonik.kiama.util.{Entity, MultipleEntity, UnknownEntity}
import org.bitbucket.inkytonik.kiama.util.Messaging.{check, checkUse, collectMessages, Messages, message}

class SemanticAnalyser(tree: VoilaTree) extends Attribution {
  val symbolTable = new SymbolTable()
  import symbolTable._

  val decorators = new Decorators(tree)
  import decorators._

  lazy val errors: Messages =
    collectMessages(tree) {
      case decl: PIdnDef if entity(decl) == MultipleEntity() =>
        message(decl, s"${decl.name} is declared more than once")

      case use: PIdnUse if entity(use) == UnknownEntity() =>
        message(use, s"${use.name} is not declared")

      case exp: PExpression if typ(exp) == PUnknownType() =>
        message(exp, s"$exp could not be typed")

      case PAssign(lhs, _) if !entity(lhs).isInstanceOf[LocalVariableEntity] =>
        message(lhs, s"Cannot assign to ${lhs.name}")

      case PHeapRead(lhs, rhs) =>
        check(entity(lhs)) {
          case LocalVariableEntity(lhsDecl) =>
            val rhsTyp = typeOfIdn(rhs)

            message(
              rhs,
              s"Type error: expected ${lhsDecl.typ}* but got $rhsTyp",
              !isCompatible(referencedType(rhsTyp), lhsDecl.typ))

          case other =>
            message(lhs, s"Type error: expected a local variable, but found $other")
        }

      case PHeapWrite(lhs, _) =>
        typeOfIdn(lhs) match {
          case _: PRefType =>
            message(lhs, "????????????????", false)
            /* TODO: Check compatibility of LHS and RHS? */
          case otherType =>
            message(
              lhs,
              s"Type error: expected a reference type, but found $otherType")
        }

      case exp: PExpression => (
           message(
              exp,
              s"Type error: expected ${expectedType(exp)} but got ${typ(exp)}",
              !isCompatible(typ(exp), expectedType(exp)))
        ++
           check(exp) {
              case PIdnExp(id) =>
                checkUse(entity(id)) {
                  case _: ProcedureEntity =>
                    message(id, "Cannot refer to procedures directly")
                }

              case PPredicateExp(id, args) =>
                checkUse(entity(id)) {
                  case PredicateEntity(decl) =>
                    reportArgumentLengthMismatch(decl.id, decl.formalArgs.length, args.length)

                  case RegionEntity(decl) =>
                    /* A region predicate has the following argument structure:
                     * one region id, several regular arguments, one optional out-argument.
-                    * Hence, the provided argument count should be formal arguments plus 1 or 2.
-                    */
                    val requiredArgCount = 1 + decl.formalArgs.length
                    val actualArgCount = args.length
                    val delta = actualArgCount - requiredArgCount
                    message(
                      id,
                        s"Wrong number of arguments for '${id.name}', got $actualArgCount "
                      + s"but expected $requiredArgCount or ${requiredArgCount + 1}",
                      delta != 0 && delta != 1)

                  case _ =>
                    message(id, s"Cannot call ${id.name}")
                }
          })
    }

  private def reportArgumentLengthMismatch(id: PIdnNode,
                                           formalArgCount: Int,
                                           actualArgCount: Int) = {

    message(
      id,
        s"Wrong number of arguments for '${id.name}', got $actualArgCount "
      + s"but expected $formalArgCount",
      formalArgCount != actualArgCount)
  }

  /**
    * Are two types compatible?  If either of them are unknown then we
    * assume an error has already been raised elsewhere so we say they
    * are compatible with anything. Otherwise, the two types have to be
    * the same.
    */
  def isCompatible(t1: PType, t2: PType): Boolean =
      (t1 == PUnknownType()) ||
      (t2 == PUnknownType()) ||
      (t1 == t2)

  /**
    * The entity defined by a defining occurrence of an identifier.
    * Defined by the context of the occurrence.
    */
  lazy val definedEntity: PIdnDef => Entity =
    attr {
      case tree.parent(p) =>
        p match {
          case decl: PProcedure => ProcedureEntity(decl)
          case decl: PPredicate => PredicateEntity(decl)
          case decl: PRegion => RegionEntity(decl)
          case decl: PGuardDecl => GuardEntity(decl, tree.parent.pair.unapply(decl).get._2.asInstanceOf[PRegion])
          case decl: PFormalArgumentDecl => ArgumentEntity(decl)
          case decl: PLocalVariableDecl => LocalVariableEntity(decl)
          case decl: PLogicalVariableBinder => LogicalVariableEntity(decl)
          case _ => UnknownEntity()
        }
    }

  /**
    * The environment to use to lookup names at a node. Defined to be the
    * completed defining environment for the smallest enclosing scope.
    */
  lazy val env: PAstNode => Environment =
    attr {
      // At a scope-introducing node, get the final value of the
      // defining environment, so that all of the definitions of
      // that scope are present.
      case tree.lastChild.pair(_: PProgram | _: PMember, c) =>
        defenv(c)

      // Otherwise, ask our parent so we work out way up to the
      // nearest scope node ancestor (which represents the smallest
      // enclosing scope).
      case tree.parent(p) =>
        env(p)
    }

  /**
    * The environment containing bindings for things that are being
    * defined. Much of the power of this definition comes from the Kiama
    * `chain` method, which threads the attribute through the tree in a
    * depth-first left-to-right order. The `envin` and `envout` definitions
    * are used to (optionally) update attribute value as it proceeds through
    * the tree.
    */
  lazy val defenv : Chain[Environment] =
    chain(defenvin, defenvout)

  def defenvin(in: PAstNode => Environment): PAstNode ==> Environment = {
    // At the root, get a new empty environment
    case program: PProgram =>
      val topLevelBindings = (
           program.regions.map(r => r.id.name -> RegionEntity(r))
        ++ program.predicates.map(p => p.id.name -> PredicateEntity(p))
        ++ program.procedures.map(p => p.id.name -> ProcedureEntity(p)))

      val guardBindings =
        program.regions.flatMap(region =>
          region.guards.map(guard =>
            s"${guard.id.name}@${region.id.name}" -> GuardEntity(guard, region)))

      rootenv(topLevelBindings ++ guardBindings :_*)

    case procedure: PProcedure =>
      val retDecl = PLocalVariableDecl(PIdnDef("ret"), procedure.typ)
      val localBindings = Vector("ret" -> LocalVariableEntity(retDecl))
      val procenv :: envs = enter(in(procedure))

      (procenv ++ localBindings) :: envs

    // At a nested scope region, create a new empty scope inside the outer
    // environment
    case scope@(_: PMember) =>
      enter(in(scope))
  }

  def defenvout(out: PAstNode => Environment): PAstNode ==> Environment = {
    // When leaving a nested scope region, remove the innermost scope from
    // the environment
    case scope@(_: PMember) =>
      leave(out(scope))

    // At a defining occurrence of an identifier, check to see if it's already
    // been defined in this scope. If so, change its entity to MultipleEntity,
    // otherwise use the entity appropriate for this definition.
    case idef: PIdnDef =>
      defineIfNew(out(idef), idef.name, definedEntity(idef))
  }

  /**********************************************************
   *  TODO: Modify environment computation such that a PPredicateExp
   *          Region(a, ...)
   *        adds a binding from a to Region to the environment
   */

  /**
    * The program entity referred to by an identifier definition or use.
    */
  lazy val entity: PIdnNode => Entity =
    attr {
      case g @ tree.parent(PGuardExp(guardId, regionId)) if guardId eq g =>
        val region = usedWithRegion(regionId)
        val guardAtRegion = s"${guardId.name}@${region.id.name}"

        lookup(env(guardId), guardAtRegion, UnknownEntity())

      case n =>
        lookup(env(n), n.name, UnknownEntity())
    }


  lazy val usedWithRegion: PIdnNode => PRegion = attr(regionIdUsedWith(_)._1)

  lazy val usedWithRegionPredicate: PIdnNode => PPredicateExp = attr(regionIdUsedWith(_)._2.get)

  lazy val regionIdUsedWith: PIdnNode => (PRegion, Option[PPredicateExp]) =
    attr(id => enclosingScope(id) match {
      case Some(scope) =>
        val regions =
          collect[Vector, Option[(PRegion, Option[PPredicateExp])]] {
            case exp @ PPredicateExp(region, PIdnExp(`id`) +: _) =>
              entity(region) match {
                case RegionEntity(decl) => Some((decl, Some(exp)))
                case _ => None
              }

            case region: PRegion if region.regionId.id.name == id.name =>
              Some(region, None)
          }

        regions(scope).flatten.head

      case None =>
        ???
    })

  def enclosingScope(of: PAstNode): Option[PMember] =
    enclosingScopeAttr(of)(of)

  private lazy val enclosingScopeAttr: PAstNode => PAstNode => Option[PMember] =
    paramAttr { of => {
      case member: PMember => Some(member)
      case tree.parent(p) => enclosingScopeAttr(of)(p)
    }}

  def interferenceSpecifications(of: PAstNode): Vector[PInterferenceClause] =
    interferenceSpecificationsAttr(of)(of)

  private lazy val interferenceSpecificationsAttr: PAstNode => PAstNode => Vector[PInterferenceClause] =
    paramAttr { of => {
      case procedure: PProcedure => procedure.inters
      case tree.parent(p) => interferenceSpecificationsAttr(of)(p)
    }}

  def enclosingMakeAtomic(of: PAstNode): PMakeAtomic =
    enclosingMakeAtomicAttr(of)(of)

  private lazy val enclosingMakeAtomicAttr: PAstNode => PAstNode => PMakeAtomic =
    paramAttr { of => {
      case makeAtomic: PMakeAtomic => makeAtomic
      case tree.parent(p) => enclosingMakeAtomicAttr(of)(p)
    }}

  def generalBindingContext(of: PLogicalVariableBinder): LogicalVariableContext =
    generalContextAttr(of.id)(of)

  def generalUsageContext(of: PIdnNode): LogicalVariableContext =
    generalContextAttr(of)(of)

  private lazy val generalContextAttr: PIdnNode => PAstNode => LogicalVariableContext =
    paramAttr { of => {
      case _: PInterferenceClause => LogicalVariableContext.Interference
      case _: PPreconditionClause => LogicalVariableContext.Precondition
      case _: PPostconditionClause => LogicalVariableContext.Postcondition
      case _: PProcedure => LogicalVariableContext.Procedure
      case _: PRegion => LogicalVariableContext.Region
      case _: PPredicate => LogicalVariableContext.Predicate
      case tree.parent(p) => generalContextAttr(of)(p)
    }}

  lazy val isGhost: PStatement => Boolean =
    attr {
      case _: PGhostStatement => true
      case compound: PCompoundStatement => compound.components forall isGhost
      case _ => false
    }

  def referencedType(typ: PType): PType =
    typ match {
      case PRefType(t) => t
      case _ => PUnknownType()
    }

  lazy val typeOfIdn: PIdnNode => PType =
    attr(entity(_) match {
      case ArgumentEntity(decl) => decl.typ
      case LocalVariableEntity(decl) => decl.typ
      case ProcedureEntity(decl) => decl.typ
      case LogicalVariableEntity(decl) => typeOfLogicalVariable(decl)
      case _ => PUnknownType()
    })

  lazy val typeOfLogicalVariable: PLogicalVariableBinder => PType =
    attr(binder => boundBy(binder) match {
      case PPointsTo(id, _) =>
        referencedType(typeOfIdn(id))

      case PPredicateExp(id, _) =>
        val region = entity(id).asInstanceOf[RegionEntity].declaration
        typ(region.state)

      case PInterferenceClause(`binder`, set, _) =>
        typ(set).asInstanceOf[PSetType].elementType

      case other => sys.error(s"Unexpectedly found $other as the binding context of $binder")
    })

  lazy val boundBy: PLogicalVariableBinder => PBindingContext =
    attr {
      case tree.parent(interferenceClause: PInterferenceClause) => interferenceClause
      case tree.parent(pointsTo: PPointsTo) => pointsTo

      case tree.parent.pair(binder: PLogicalVariableBinder,
                            predicateExp @ PPredicateExp(id, arguments)) =>

        val region =
          entity(id) match {
            case regionEntity: RegionEntity => regionEntity.declaration
            case _ => sys.error(s"Logical variables cannot yet be bound by a non-region predicate: $predicateExp")
          }

        val idx = arguments.indices.find(arguments(_) == binder).get
        assert(idx == region.formalArgs.length + 1,
                 s"Expected logical variable binder $binder to be the last predicate argument, "
               + s"but got $predicateExp")

        predicateExp
    }

  /**
    * What is the type of an expression?
    */
  lazy val typ: PExpression => PType =
    attr {
      case _: PIntLit => PIntType()
      case _: PTrueLit | _: PFalseLit => PBoolType()

      case ret: PRet => enclosingScope(ret).get.asInstanceOf[PProcedure].typ

      case PIdnExp(id) => typeOfIdn(id)

      case _: PAdd | _: PSub => PIntType()
      case _: PAnd | _: POr | _: PNot => PBoolType()
      case _: PEquals | _: PLess | _: PAtMost | _: PGreater | _: PAtLeast => PBoolType()

      case _: PIntSet | _: PNatSet => PSetType(PIntType())

      case PExplicitSet(elements, typeAnnotation) =>
        typeAnnotation match {
          case Some(_typ) => PSetType(_typ)
          case None => PSetType(typ(elements.head))
        }

      case conditional: PConditional => typ(conditional.thn)

      case _: PPointsTo | _: PPredicateExp | _: PGuardExp | _: PTrackingResource => PBoolType()

      case tree.parent.pair(_: PIrrelevantValue, p: PExpression) => typ(p)

      case binder: PLogicalVariableBinder => typeOfLogicalVariable(binder)

      case _ => PUnknownType()
    }

  /**
    * What is the expected type of an expression?
    */
  lazy val expectedType: PExpression => PType =
    attr {
      case tree.parent(_: PIf | _: PWhile) => PBoolType()

      case tree.parent(PAssign(id, _)) =>
        entity(id) match {
          case LocalVariableEntity(decl) => decl.typ
          case _ => PUnknownType()
        }

      case tree.parent(PHeapRead(id, _)) => referencedType(typeOfIdn(id))

      case tree.parent(PHeapWrite(id, _)) => referencedType(typeOfIdn(id))

      case e @ tree.parent(PEquals(lhs, rhs)) if e eq rhs =>
        /* The expected type of the RHS of an equality is the type of the LHS */
        typ(lhs)

      case tree.parent(_: PAdd | _: PSub) => PIntType()
      case tree.parent(_: PAnd | _: POr | _: PNot) => PBoolType()
      case tree.parent(_: PLess | _: PAtMost | _: PGreater | _: PAtLeast) => PIntType()

      case cnd @ tree.parent(conditional: PConditional) if cnd eq conditional.cond => PBoolType()
      case els @ tree.parent(conditional: PConditional) if els eq conditional.els => typ(conditional.thn)

      case _ =>
        /* Returning unknown expresses that no particular type is expected */
        PUnknownType()
    }
}

sealed trait LogicalVariableContext
object LogicalVariableContext {
  case object Interference extends LogicalVariableContext
  case object Precondition extends LogicalVariableContext
  case object Postcondition extends LogicalVariableContext
  case object Procedure extends LogicalVariableContext
  case object Region extends LogicalVariableContext
  case object Predicate extends LogicalVariableContext
}
