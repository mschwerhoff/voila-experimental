/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila.frontend

import org.bitbucket.inkytonik.kiama.==>
import org.bitbucket.inkytonik.kiama.attribution.{Attribution, Decorators}
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

      case decl: PIdnUse if entity(decl) == UnknownEntity() =>
        message(decl, s"${decl.name} is not declared")

      case PAssign(lhs, _) if !entity(lhs).isInstanceOf[LocalVariableEntity] =>
        message(lhs, s"Cannot assign to ${lhs.name}")

      case PHeapRead(lhs, rhs) =>
        checkUse(entity(lhs)) { case VariableEntity(lhsDecl) =>
          checkUse(entity(rhs)) { case VariableEntity(rhsDecl) => (
               message(
                 rhs,
                 s"Type error: expected a reference type, but found ${rhsDecl.typ}",
                 !rhsDecl.typ.isInstanceOf[PRefType])
            ++
               message(
                 rhs,
                 s"Type error: expected ${lhsDecl.typ}* but got ${rhsDecl.typ}",
                 !isCompatible(referencedType(rhsDecl.typ), lhsDecl.typ)))
          }
        }

      case PHeapWrite(lhs, rhs) =>
        checkUse(entity(lhs)) { case VariableEntity(lhsDecl) =>
           message(
             lhs,
             s"Type error: expected a reference type, but found ${lhsDecl.typ}",
             !lhsDecl.typ.isInstanceOf[PRefType])
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

              case PCall(id, args) =>
                checkUse(entity(id)) {
                  case ProcedureEntity(decl) =>
                    reportArgumentLengthMismatch(decl.id, decl.formalArgs, args)

                  case PredicateEntity(decl) =>
                    reportArgumentLengthMismatch(decl.id, decl.formalArgs, args)

                  case _ =>
                    message(id, s"Cannot call ${id.name}")
                }
          })
    }

  private def reportArgumentLengthMismatch(id: PIdnNode,
                                           formalArgs: Vector[PFormalArgumentDecl],
                                           args: Vector[PExpression]) = {
    message(
      id,
        s"Wrong number of arguments for '${id.name}', got ${args.length} "
      + s"but expected ${formalArgs.length}",
      formalArgs.length != args.length)
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
          case decl: PFormalArgumentDecl => ArgumentEntity(decl)
          case decl: PLocalVariableDecl => LocalVariableEntity(decl)
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
           program.predicates.map(p => p.id.name -> PredicateEntity(p))
        ++ program.procedures.map(p => p.id.name -> ProcedureEntity(p)))

      rootenv(topLevelBindings: _*)

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

  /**
    * The program entity referred to by an identifier definition or use.
    */
  lazy val entity: PIdnNode => Entity =
    attr {
//      // If we are looking at an identifier used as a method call,
//      // we need to look it up in the environment of the class of
//      // the object it is being called on. E.g., `o.m` needs to
//      // look for `m` in the class of `o`, not in local environment.
//      case tree.parent.pair(IdnUse(i), CallExp(base, _, _)) =>
//          tipe(base) match {
//              case ReferenceType(decl) =>
//                  findMethod(decl, i)
//              case t =>
//                  UnknownEntity()
//          }

      // Otherwise, just look the identifier up in the environment
      // at the node. Return `UnknownEntity` if the identifier is
      // not defined.
      case n =>
        lookup(env(n), n.name, UnknownEntity())
    }

  /**
    * Return the internal type of a syntactic type. In most cases they
    * are the same. The exception is class types since the class type
    * refers to the class by name, but we need to have it as a reference
    * type that refers to the declaration of that class.
    */
  def actualType(typ: PType): PType =
    typ match {
//        case ClassType(idn) =>
//            entity(idn) match {
//                case ClassEntity(decl) =>
//                    ReferenceType(decl)
//                case _ =>
//                    UnknownType()
//            }
      case _ => typ
    }

  def referencedType(typ: PType): PType =
    typ match {
      case PRefType(t) => t
      case _ => PUnknownType()
    }

  /**
    * What is the type of an expression?
    */
  lazy val typ: PExpression => PType =
    attr {
      case _: PIntLit => PIntType()
      case _: PTrueLit | _: PFalseLit => PBoolType()

      case PIdnExp(id) =>
        entity(id) match {
          case VariableEntity(decl) => actualType(decl.typ)
          case _ => PUnknownType()
        }

      case _: PAdd | _: PSub => PIntType()
      case _: PAnd | _: POr | _: PNot => PBoolType()
      case _: PLess | _: PAtMost | _: PGreater | _: PAtLeast => PBoolType()

//          case CallExp(_, i, _) =>
//              entity(i) match {
//                  case MethodEntity(decl) =>
//                      actualTypeOf(decl.body.tipe)
//                  case _ =>
//                      UnknownType()
//              }

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
          case LocalVariableEntity(decl) => actualType(decl.typ)
          case _ => PUnknownType()
        }

      case tree.parent(PHeapRead(id, _)) =>
        entity(id) match {
          case VariableEntity(decl) => referencedType(decl.typ)
          case _ => PUnknownType()
        }

      case tree.parent(PHeapWrite(id, _)) =>
        entity(id) match {
          case VariableEntity(decl) => referencedType(decl.typ)
          case _ => PUnknownType()
        }

      case tree.parent(_: PAdd | _: PSub) => PIntType()
      case tree.parent(_: PAnd | _: POr | _: PNot) => PBoolType()
      case tree.parent(_: PLess | _: PAtMost | _: PGreater | _: PAtLeast) => PIntType()

//        case e @ tree.parent(CallExp(base, u, _)) if base eq e =>
//            UnknownType()
//
//        case e @ tree.parent(CallExp(_, u, _)) =>
//            entity(u) match {
//                case MethodEntity(decl) =>
//                    expTypeOfArg(decl, tree.index(e))
//
//                case _ =>
//                    // No idea what is being called, so no type constraint
//                    UnknownType()
//            }

//        case tree.parent(tree.parent.pair(_ : Result, MethodBody(t, _, _, _, _))) =>
//            actualTypeOf(t)

      case _ =>
        /* Returning unknown expresses that no particular type is expected */
        PUnknownType()
    }
}
