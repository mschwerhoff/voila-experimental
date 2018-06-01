/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila.translator

import viper.silver.ast._

import scala.collection.{breakOut, mutable}
import viper.silver.{ast => vpr}
import viper.silver.verifier.{errors => vprerr, reasons => vprrea}
import viper.voila.backends.ViperAstUtils
import viper.voila.frontend._
import viper.voila.reporting.{FoldError, InsufficientRegionPermissionError, InterferenceError, PreconditionError, RegionStateError, UnfoldError}
import viper.voila.translator.TranslatorUtils._


trait InterferenceTranslatorComponent { this: PProgramToViperTranslator =>

  val interferenceSetFunctions: FrontResource[PRegion] = {
    val _name = "interferenceSet"
    def _functionType(obj: PRegion): Type = vpr.SetType(regionStateFunction(obj).typ)

    val _footprintManager =
      new FootprintManager[PRegion]
        with RegionManager[vpr.Predicate, vpr.PredicateAccessPredicate]
        with RemoveVersionSelector[PRegion] {
        override val name: String = _name
      }

    val _triggerManager =
      new DomainFunctionManager[PRegion]
        with RegionManager[vpr.DomainFunc, vpr.DomainFuncApp]
        with SubFullSelector[PRegion] {
        override val name: String = _name
        override def functionTyp(obj: PRegion): Type = _functionType(obj)
      }

    new HeapFunctionManager[PRegion]
      with RegionManager[vpr.Function, vpr.FuncApp]
      with VersionedSelector[PRegion] {

      override val footprintManager: FootprintManager[PRegion] with SubSelector[PRegion] = _footprintManager
      override val triggerManager: DomainFunctionManager[PRegion] with SubSelector[PRegion] = _triggerManager

      override def functionTyp(obj: PRegion): Type = _functionType(obj)
      override val name: String = _name

      override protected def post(trigger: DomainFuncApp): Vector[Exp] = {

        val varName = "$_m" // TODO: naming convention
        val varType = trigger.typ match { case vpr.SetType(t) => t }
        val varDecl = vpr.LocalVarDecl(varName, varType)()
        val variable = varDecl.localVar

        val varInResult =
          vpr.AnySetContains(
            variable,
            vpr.Result()(typ = trigger.typ)
          )()

        val varInTrigger =
          vpr.AnySetContains(
            variable,
            trigger
          )()

        Vector(
          vpr.Forall(
            Vector(varDecl),
            Vector(vpr.Trigger(Vector(varInResult))()),
            vpr.Implies(varInResult, varInTrigger)()
          )()
        )
      }

      override protected def triggerApplication(id: PRegion, args: Vector[Exp]): Exp = args match {
        case (xs :+ m) => vpr.AnySetContains(m, triggerManager.application(id, xs))()
      }
    }
  }




  /* Interference-Set Domain and Domain-Functions */


  def interfereceWrapperExtension(region: PRegion, postState: Vector[vpr.Exp] => vpr.Exp)
                                 (selectWrapper: QuantifierWrapper.StmtWrapper): QuantifierWrapper.StmtWrapper = {

    val varName = "$_m" // TODO: naming convention
    val varType = regionStateFunction(region).typ
    val varDecl = vpr.LocalVarDecl(varName, varType)()
    val variable = varDecl.localVar

    val regionArgs = selectWrapper.param
    val newState = postState(regionArgs)

    /* exp in X(xs)*/
    def expInSet(exp: vpr.Exp) = vpr.AnySetContains(
      exp,
      interferenceSetFunctions.application(region, regionArgs)
    )()

    /* m in X(xs) <==> e(xs)[postState(xs) -> m] */
    def pre(exp: vpr.Exp): vpr.Exp = {
      val substExp = exp.transform{ case `newState` => variable}
      vpr.EqCmp(expInSet(variable), substExp)()
    }

    /* inhale exp; Q(postState(xs) in X(xs)) */
    def post(exp: vpr.Exp): vpr.Stmt = {
      val selectSet = vpr.Inhale(exp)()
      val selectState = selectWrapper.wrap(expInSet(newState))

      /* acc(R_inference_fp()) */
      val interferenceFootprintAccess = null

      /* exhale acc(R_inference_fp()) */
      val vprExhaleInterferenceFootprintAccess =
        vpr.Exhale(interferenceFootprintAccess)()

      /* inhale acc(R_inference_fp()) */
      val vprInhaleInterferenceFootprintAccess =
        vpr.Inhale(interferenceFootprintAccess)()

      vpr.Seqn(
        Vector(
          vprExhaleInterferenceFootprintAccess,
          vprInhaleInterferenceFootprintAccess,
          selectSet,
          selectState
        ),
        Vector.empty
      )()
    }

    val triggers = Vector(vpr.Trigger(Vector(expInSet(variable)))())

    QuantifierWrapper.AllWrapper(Vector(varDecl), regionArgs, triggers)(post, pre)
  }


  def initInterference(statement: vpr.Stmt): vpr.Stmt = {
    ???
  }

  def linkInterference() = ???

  def queryInterference() = ???

  def nextStateContainedInInference(region: PRegion): Constraint = Constraint( args => target =>
    TranslatorUtils.BetterQuantifierWrapper.UnitWrapperExt(
      vpr.AnySetContains(
        target,
        interferenceSetFunctions.application(region, args)
      )()
    )
  )

  def containsAllPossibleNextStatesConstraint(region: PRegion, possibleNextStateConstraint: Constraint): Constraint = {
    def constrain(args: Vector[Exp])(target: Exp): TranslatorUtils.BetterQuantifierWrapper.WrapperExt = {

      val varName = "$_m" // TODO: naming convention
      val varType = regionStateFunction(region).typ
      val varDecl = vpr.LocalVarDecl(varName, varType)()
      val variable = varDecl.localVar

      val regionArgs = args

      /* m in X(xs)*/
      val varInInterference =
        vpr.AnySetContains(
          variable,
          target
        )()

      val varIsPossibleNextState: TranslatorUtils.BetterQuantifierWrapper.WrapperExt =
        possibleNextStateConstraint.constrain(args)(variable)

      /* m in X(xs) <==> preState(xs) ~> m */
      varIsPossibleNextState.combine(e =>
        TranslatorUtils.BetterQuantifierWrapper.QuantWrapperExt(
          Vector(varDecl),
          vpr.EqCmp(varInInterference, e)()
        )
      )
    }

    Constraint(constrain, possibleNextStateConstraint.skolemization)
  }

}
