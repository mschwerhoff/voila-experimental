/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.voila.translator

import scala.collection.breakOut
import org.bitbucket.inkytonik.kiama.rewriting.Rewriter.{collect, collectall}
import viper.silver.{ast => vpr}
import viper.silver.verifier.{errors => vprerr, reasons => vprrea}
import viper.voila.backends.ViperAstUtils
import viper.voila.frontend._
import viper.voila.reporting.{FoldError, InsufficientRegionPermissionError, InterferenceError, PreconditionError, RegionStateError, UnfoldError}

trait MainTranslatorComponent { this: PProgramToViperTranslator =>

  /*
   * Mutable state
   */

  private var translationIndentation = 0
  private def indent(depth: Int = translationIndentation): String = " " * 2 * depth

  private var _tree: VoilaTree = _
  protected def tree: VoilaTree = _tree

  private var _errorBacktranslator: ErrorBacktranslator = _
  protected def errorBacktranslator: ErrorBacktranslator = _errorBacktranslator

  private var _translatedProcedureStubs: Map[String, vpr.Method] = _
  protected def translatedProcedureStubs: Map[String, vpr.Method] = _translatedProcedureStubs

  protected def outputDebugInfo(message: String): Unit = logger.debug(s"${indent()}$message")

  /*
   * Immutable state and utility methods
   */

  val diamondField: vpr.Field =
    vpr.Field("$diamond", vpr.Int)()

  def diamondLocation(rcvr: vpr.Exp): vpr.FieldAccess =
    vpr.FieldAccess(rcvr, diamondField)()

  def diamondAccess(rcvr: vpr.Exp): vpr.FieldAccessPredicate =
    vpr.FieldAccessPredicate(diamondLocation(rcvr), vpr.FullPerm()())()

  def stepFromField(typ: PType): vpr.Field =
    vpr.Field(nameSanitizer.sanitize(s"$$stepFrom_$typ"), translate(typ))()

  def stepFromLocation(rcvr: vpr.Exp, typ: PType): vpr.FieldAccess =
    vpr.FieldAccess(rcvr, stepFromField(typ))()

  def stepFromAccess(rcvr: vpr.Exp, typ: PType): vpr.FieldAccessPredicate =
    vpr.FieldAccessPredicate(stepFromLocation(rcvr, typ), vpr.FullPerm()())()

  def stepToField(typ: PType): vpr.Field =
    vpr.Field(nameSanitizer.sanitize(s"$$stepTo_$typ"), translate(typ))()

  def stepToLocation(rcvr: vpr.Exp, typ: PType): vpr.FieldAccess =
    vpr.FieldAccess(rcvr, stepToField(typ))()

  def stepToAccess(rcvr: vpr.Exp, typ: PType): vpr.FieldAccessPredicate =
    vpr.FieldAccessPredicate(stepToLocation(rcvr, typ), vpr.FullPerm()())()

  val atomicityContextsDomainName: String = "$AtomicityContexts"

  def atomicityContextsDomain(regions: Vector[PRegion]): vpr.Domain =
    vpr.Domain(
      name = atomicityContextsDomainName,
      functions = regions.map(atomicityContextFunction),
      axioms = Vector.empty,
      typVars = Vector.empty
    )()

  def atomicityContextFunctionName(regionName: String): String =
    s"${regionName}X"

  def atomicityContextFunction(regionId: PIdnNode): vpr.DomainFunc = {
    val region = semanticAnalyser.usedWithRegion(regionId)

    atomicityContextFunction(region)
  }

  def atomicityContextFunction(region: PRegion): vpr.DomainFunc = {
    val vprType = vpr.SetType(regionStateFunction(region).typ)

    vpr.DomainFunc(
      name = atomicityContextFunctionName(region.id.name),
      formalArgs = region.formalInArgs map translate,
      typ = vprType
    )(vpr.NoPosition, vpr.NoInfo, atomicityContextsDomainName, vpr.NoTrafos)
  }

  def localVariableDeclaration(binder: PNamedBinder): vpr.LocalVarDecl = {
    vpr.LocalVarDecl(
      binder.id.name,
      translate(semanticAnalyser.typeOfIdn(binder.id))
    )()
  }

  protected var collectedDeclarations: List[vpr.Declaration] = Nil
  protected var initializingFunctions: List[PRegion => vpr.Stmt] = Nil

  protected var collectedVariableDeclarations: Vector[vpr.LocalVarDecl] = Vector.empty

  def declareFreshVariable(typ: PType, basename: String): vpr.LocalVarDecl =
    declareFreshVariable(translate(typ), basename)

  def declareFreshVariable(typ: vpr.Type, basename: String): vpr.LocalVarDecl = {
    val decl = vpr.LocalVarDecl(s"$$$basename${collectedVariableDeclarations.length}", typ)()

    collectedVariableDeclarations = collectedVariableDeclarations :+ decl

    decl
  }

  def havocMethodName(typ: vpr.Type): String =
    nameSanitizer.sanitize(s"havoc_$typ")

  /* TODO: Reconsider use of havoc methods:
   *         - Avoid introducing a havoc method for each type in the source program
   *         - Maybe use fresh variables instead? What about loops?
   *         - Rename method?
   */
  def usedVariableHavocs(tree: VoilaTree): Vector[vpr.Method] = {
    val voilaTypes =
      PBoolType() +:
      tree.nodes.collect { case typ: PType => typ }

    val vprTypes = voilaTypes.map(translate).distinct

    vprTypes map (typ =>
      vpr.Method(
        name = havocMethodName(typ),
        /* TODO: Name sanitisation must be done (more) systematically */
        formalArgs = Vector.empty,
        formalReturns = Vector(vpr.LocalVarDecl("$r", typ)()),
        pres = Vector.empty,
        posts = Vector.empty,
        body = Some(vpr.Seqn(Vector.empty, Vector.empty)())
      )()
    )
  }

  def havoc(variable: PIdnUse): vpr.Stmt = {
    havoc(translateUseOf(variable).asInstanceOf[vpr.LocalVar])
  }

  def havoc(variable: vpr.LocalVar): vpr.Stmt = {
    vpr.MethodCall(
      havocMethodName(variable.typ),
      Vector.empty,
      Vector(variable)
    )(vpr.NoPosition, vpr.NoInfo, vpr.NoTrafos)
  }

  def extractLogicalVariableBindings(assertion: PExpression): Vector[vpr.LocalVarAssign] = {
    val collectBindings =
      collectall[Vector, vpr.LocalVarAssign] {
        case PPointsTo(location, binder: PNamedBinder) =>
          val vprVar = localVariableDeclaration(binder).localVar
          val vprValue = translate(location)

          Vector(vpr.LocalVarAssign(vprVar, vprValue)())

        case predicateExp: PPredicateExp =>
          semanticAnalyser.entity(predicateExp.predicate) match {
            case _: RegionEntity =>
              val (region, inArgs, outArgsAndState) = getRegionPredicateDetails(predicateExp)
              val vprInArgs = inArgs map translate

              outArgsAndState.zipWithIndex.flatMap {
                case (binder: PNamedBinder, index) =>
                  val vprVar = localVariableDeclaration(binder).localVar

                  val vprValue =
                    vpr.FuncApp(
                      regionOutArgumentFunction(region, index),
                      vprInArgs
                    )()

                  val vprAssignment =
                    vpr.LocalVarAssign(
                      vprVar,
                      vprValue
                    )()

                  errorBacktranslator.addErrorTransformer {
                    case e @ vprerr.PreconditionInAppFalse(_, _: vprrea.InsufficientPermission, _)
                         if e causedBy vprValue =>

                      InsufficientRegionPermissionError(predicateExp)
                  }

                  Vector(vprAssignment)

                case _ =>  Vector.empty
              }

            case _ => Vector.empty
          }
      }

    collectBindings(assertion)
  }

  /*
   * Main functionality
   */

  def translate(tree: VoilaTree): (vpr.Program, ErrorBacktranslator) = {
    _tree = tree
    _errorBacktranslator = new DefaultErrorBacktranslator

    analyseSetComprehensions(tree)

    // TODO: tree.root appears to be a costly operation rather than a simple getter - investigate

    _translatedProcedureStubs =
      tree.root.procedures.map(procedure =>
        procedure.id.name -> translateAsStub(procedure)
      )(breakOut)

    val topLevelDeclarations: Vector[vpr.Declaration] = (
         Vector(
            diamondField,
            regionStateTriggerFunctionDomain,
            atomicityContextsDomain(tree.root.regions))
      ++ collectedDeclarations
      ++ usedVariableHavocs(tree)
      ++ recordedSetComprehensions.values
      ++ tree.root.regions.flatMap(region => {
            val typ = semanticAnalyser.typ(region.state)

            Vector(stepFromField(typ), stepToField(typ))
         }).distinct
      ++ (tree.root.structs flatMap translate)
      ++ (tree.root.regions flatMap translate)
      ++ (tree.root.predicates map translate)
      ++ (tree.root.procedures map translate)
    )

    val fields = topLevelDeclarations collect { case f: vpr.Field => f }
    val predicates = topLevelDeclarations collect { case p: vpr.Predicate => p }
    val functions = topLevelDeclarations collect { case f: vpr.Function => f }
    val methods = topLevelDeclarations collect { case m: vpr.Method => m }

    val additionalDomainFunctions: Map[String, Vector[vpr.DomainFunc]] =
      topLevelDeclarations
        .collect { case d: vpr.DomainFunc => d }
        .groupBy(_.domainName)
        .withDefaultValue(Vector.empty)

    val additionalDomainAxioms: Map[String, Vector[vpr.DomainAxiom]] =
      topLevelDeclarations
        .collect { case d: vpr.DomainAxiom => d }
        .groupBy(_.domainName)
        .withDefaultValue(Vector.empty)

    val domains =
      topLevelDeclarations
        .collect { case d: vpr.Domain => d }
        .map(domain =>
          domain.copy(
            functions = domain.functions ++ additionalDomainFunctions(domain.name),
            axioms = domain.axioms ++ additionalDomainAxioms(domain.name)
          )(domain.pos, domain.info, domain.errT)
        )

    val vprProgram =
      vpr.Program(
        domains = domains,
        fields = fields,
        functions = functions,
        predicates = predicates,
        methods = methods
      )()

    val backtranslator = _errorBacktranslator

    _tree = null
    _errorBacktranslator = null
    _translatedProcedureStubs = null

    (vprProgram, backtranslator)
  }

  def translate(member: PMember): vpr.Member =
    member match {
      case s: PStruct => translate(s)
      case r: PRegion => translate(r)
      case p: PPredicate => translate(p)
      case p: PProcedure => translate(p)
    }

  def translate(struct: PStruct): Vector[vpr.Field] = {
    struct.fields.map(field => toField(struct, field.id))
  }

  def translate(predicate: PPredicate): vpr.Predicate = {
    vpr.Predicate(
      name = predicate.id.name,
      formalArgs = predicate.formalArgs map translate,
      body = predicate.body.map(translate)
    )()
  }

  def translate(procedure: PProcedure): vpr.Method = {
    assert(collectedVariableDeclarations.isEmpty)

    val vprMethod =
      procedure.atomicity match {
        case PAbstractAtomic() =>
          logger.debug(s"\nTranslating abstract-atomic procedure ${procedure.id.name}")
          translateAbstractlyAtomicProcedure(procedure)
        case PNonAtomic() =>
          logger.debug(s"\nTranslating non-atomic procedure ${procedure.id.name}")
          translateStandardProcedure(procedure)
        case PPrimitiveAtomic() =>
          logger.debug(s"\nTranslating primitive-atomic procedure ${procedure.id.name}")
          translateStandardProcedure(procedure)
    }

    val inhaleSkolemizationFunctionFootprints =
      vpr.Inhale(
        viper.silicon.utils.ast.BigAnd(
          /* TODO: Would benefit from an optimisation similar to issue #47 */
          tree.root.regions map (region =>
            actionSkolemizationFunctionFootprintAccess(region.id.name)))
      )()

    val initializeFootprints = initializingFunctions.flatMap(tree.root.regions map _)

//    val inhaleInterferenceFunctionFootprints =
//      vpr.Inhale(
//        viper.silicon.utils.ast.BigAnd(
//          /* TODO: Would benefit from an optimisation similar to issue #47 */
//          tree.root.regions map (region =>
//            interferenceSetFunctionManager.footprintAccess(region)))
//      )()

    val procedureWideBoundLogicalVariableDeclarations: Vector[vpr.LocalVarDecl] = {
      procedure.body match {
        case Some(body) =>
          AstUtils.extractNamedBindersFromGhostStatements(body).map(localVariableDeclaration)
        case None =>
          Vector.empty
      }
    }

    val bodyWithPreamble: Option[vpr.Seqn] =
      vprMethod.body.map(actualBody =>
        actualBody.copy(
          ss =
            inhaleSkolemizationFunctionFootprints +: initializeFootprints ++:
            actualBody.ss,
          scopedDecls =
              actualBody.scopedDecls ++
              procedureWideBoundLogicalVariableDeclarations ++
              collectedVariableDeclarations
        )(actualBody.pos, actualBody.info, actualBody.errT))

    collectedVariableDeclarations = Vector.empty

    val bodyToVerify: Option[vpr.Seqn] =
      if (!config.include.supplied || config.include().contains(procedure.id.name))
        bodyWithPreamble
      else
        None

    vprMethod.copy(
      body = bodyToVerify
    )(vprMethod.pos, vprMethod.info, vprMethod.errT)
  }

  def translateAsStub(procedure: PProcedure): vpr.Method = {
    val (vprPres, vprPosts) =
      procedure.atomicity match {
        case PAbstractAtomic() =>
          val pres =
            procedure.pres.map(pre => translate(pre.assertion)) ++
            procedure.inters.map(translate)

          val posts = procedure.posts.map(post => translate(post.assertion))

          (pres, posts)

        case PNonAtomic() | PPrimitiveAtomic() =>
          val pres = procedure.pres.map(pre => translate(pre.assertion))
          val posts = procedure.posts map (post => translate(post.assertion))

          (pres, posts)
      }

    vpr.Method(
      name = procedure.id.name,
      formalArgs = procedure.formalArgs map translate,
      formalReturns = procedure.formalReturns map translate,
      pres = vprPres,
      posts = vprPosts,
      body = None
    )().withSource(procedure)
  }

  def translateAbstractlyAtomicProcedure(procedure: PProcedure): vpr.Method = {
    case class Entry(clause: PInterferenceClause,
                     regionPredicate: PPredicateExp,
                     atomicityContext: vpr.DomainFuncApp,
                     interferenceSet: vpr.FuncApp,
                     referencePoint: vpr.FuncApp,
                     regionState: vpr.FuncApp)

    val regionInterferenceVariables: Map[PIdnUse, Entry] =
      procedure.inters.map(inter => {
        val regionId = inter.region
        val regionPredicate = semanticAnalyser.usedWithRegionPredicate(regionId)
        val vprAtomicityContext = {
          val (_, regionArguments, _) = getAndTranslateRegionPredicateDetails(regionPredicate)

          vpr.DomainFuncApp(
            atomicityContextFunction(regionId),
            regionArguments,
            Map.empty[vpr.TypeVar, vpr.Type]
          )()
        }

        val vprInterferenceSet = {
          val (reg, regionArguments, _) = getAndTranslateRegionPredicateDetails(regionPredicate)

          interferenceSetFunctions.application(reg, regionArguments)
        }

        val referencePoint = {
          val (reg, regionArguments, _) = getAndTranslateRegionPredicateDetails(regionPredicate)

          interferenceReferenceFunctions.application(reg, regionArguments)
        }

        val regionState = {
          val (reg, regionArguments, _) = getAndTranslateRegionPredicateDetails(regionPredicate)

          vpr.FuncApp(
            regionStateFunction(reg),
            regionArguments
          )()
        }

        regionId -> Entry(inter, regionPredicate.asInstanceOf[PPredicateExp], vprAtomicityContext, vprInterferenceSet, referencePoint, regionState)
      }).toMap

    val vprLocals = procedure.locals.map(translate)

    val vprBody = {
      val vprSaveInferenceSets: Vector[vpr.Stmt] =
        regionInterferenceVariables.map { case (_, entry) => {


          val translatedClauseSet = translate(entry.clause.set)

          vpr.Seqn(
            Vector(
              vpr.Inhale( // TODO remove old atomicity context
                vpr.EqCmp(
                  entry.atomicityContext,
                  translatedClauseSet
                )()
              )(),
              vpr.Inhale( // TODO add label
                vpr.EqCmp(
                  entry.interferenceSet,
                  translatedClauseSet
                )()
              )(),
              vpr.Inhale(
                vpr.EqCmp(
                  entry.referencePoint,
                  vpr.Old(entry.regionState)()
                )()
              )()
            ),
            Vector.empty
          )()}
        }(breakOut)

      val vprMainBody =
        procedure.body match {
          case Some(stmt) => translate(stmt)
          case None => vpr.Inhale(vpr.FalseLit()())()
        }

      vpr.Seqn(vprSaveInferenceSets :+ vprMainBody, vprLocals)()
    }

    val vprStub = translatedProcedureStubs(procedure.id.name)

    vprStub.copy(
      body = Some(vprBody)
    )(vprStub.pos, vprStub.info, vprStub.errT)
  }

  def translateStandardProcedure(procedure: PProcedure): vpr.Method = {
    require(
      procedure.inters.isEmpty,
      s"Expected procedure ${procedure.id} to have no interference clause, "
        + s"but found ${procedure.inters}")

    val inferInterferenceSets = inferContextAllInstances("beginning of non atomic procedure")

    val vprBody = {
      val mainBody =
        procedure.body match {
          case Some(stmt) => translate(stmt)
          case None => vpr.Inhale(vpr.FalseLit()())()
        }

      vpr.Seqn(Vector(inferInterferenceSets, mainBody), procedure.locals map translate)()
    }

    val vprStub = translatedProcedureStubs(procedure.id.name)

    vprStub.copy(
      body = Some(vprBody)
    )(vprStub.pos, vprStub.info, vprStub.errT)
  }

  def translate(declaration: PVariableDeclaration): vpr.LocalVarDecl =
    translateAndRename(declaration, Predef.identity)

  def translateAndRename(declaration: PVariableDeclaration, rename: String => String): vpr.LocalVarDecl = {
    vpr.LocalVarDecl(rename(declaration.id.name), translate(declaration.typ))()
  }

  def translate(interference: PInterferenceClause): vpr.AnySetContains = {
    val vprSet = translate(interference.set)
    val predicateExp = semanticAnalyser.usedWithRegionPredicate(interference.region)
    val vprRegionState = regionState(predicateExp)

    val vprRegionConstraint =
      vpr.AnySetContains(vprRegionState, vprSet)().withSource(interference)

    errorBacktranslator.addReasonTransformer {
      case e: vprrea.AssertionFalse if e causedBy vprRegionConstraint => InterferenceError(interference)
    }

    vprRegionConstraint
  }

  def translate(statement: PStatement): vpr.Stmt = {
    logger.debug(s"${indent()}${statement.getClass.getSimpleName}")
    statement match {
      case _: PCompoundStatement => translationIndentation += 1
      case _ =>
    }

    if (!semanticAnalyser.isGhost(statement) &&
        semanticAnalyser.atomicity(statement) == AtomicityKind.Nonatomic &&
        semanticAnalyser.expectedAtomicity(statement) == AtomicityKind.Atomic) {

      sys.error( "Unexpectedly found a non-atomic and non-ghost statement " +
                s"(${statement.statementName}) in an atomic context " +
                s"(@${statement.lineColumnPosition})")
    }

    val vprStatement = directlyTranslate(statement)

    def mustHavocAfter(s: PStatement): Boolean = {
      semanticAnalyser.atomicity(s) == AtomicityKind.Nonatomic ||
        (semanticAnalyser.atomicity(s) == AtomicityKind.Atomic &&
         !semanticAnalyser.isGhost(s) &&
         semanticAnalyser.expectedAtomicity(s) == AtomicityKind.Nonatomic)
    }

    val optVprHavoc =
      if (mustHavocAfter(statement)) {
        val alreadyHavoced =
          statement match {
            case PSeqComp(_, second) => mustHavocAfter(second)
            case PIf(_, thn, els) => mustHavocAfter(thn) && els.fold(true)(mustHavocAfter)
            case _: PWhile => false
            case _: PMakeAtomic => false
            case _: PUpdateRegion => false
            case _: PUseAtomic => false
            case _: POpenRegion => false
            case _: PCompoundStatement => ??? /* Forgot about a particular compound statement */
            case _ => false
          }

        if (alreadyHavoced) {
          None
        } else {
          Some(nonAtomicStabilizeAllInstances(s"after ${statement.statementName}@${statement.lineColumnPosition}"))
        }
      } else {
        None
      }

    statement match {
      case _: PCompoundStatement => translationIndentation -= 1
      case _ =>
    }

    vpr.Seqn(
      vprStatement +: optVprHavoc.toVector,
      Vector.empty
    )()
  }

  protected def directlyTranslate(statement: PStatement): vpr.Stmt = {
    statement match {
      case seqComp: PSeqComp =>
        val vprFirstStmt = translate(seqComp.first)
        val vprSecondStmt = translate(seqComp.second)

        vpr.Seqn(
          Vector(vprFirstStmt, vprSecondStmt),
          Vector.empty
        )()

      case PSkip() =>
        ViperAstUtils.Seqn()(info = vpr.SimpleInfo(Vector("", "skip;", "")))

      case PIf(cond, thn, els) =>
        var vprIf =
          vpr.If(
            translate(cond),
            vpr.Seqn(Vector(translate(thn)), Vector.empty)(),
            vpr.Seqn(els.toSeq.map(translate), Vector.empty)()
          )()

        vprIf = vprIf.withSource(statement)

        surroundWithSectionComments(statement.statementName, vprIf)

      case PWhile(cond, invs, body) =>
        val inhaleSkolemizationFunctionFootprints =
          vpr.Inhale(
            viper.silicon.utils.ast.BigAnd(
              /* TODO: Would benefit from an optimisation similar to issue #47 */
              tree.root.regions map (region =>
                actionSkolemizationFunctionFootprintAccess(region.id.name)))
          )()

        val initializeFootprints = initializingFunctions.flatMap(tree.root.regions map _)

        val preAtomicityLabel = freshLabel("preWhile")

        val atomicityConstraints = tree.root.regions map (region => {
          val wrapper = regionAllWrapper(region, exp => exp)
          val constraint = atomicityContextWhileConstraint(region, preAtomicityLabel)
          atomicityContextFunctions.select(region, constraint)(wrapper)
        })

        val inferContext = inferContextAllInstances("infer context inside while")

        val vprBody =
          vpr.Seqn(
            inhaleSkolemizationFunctionFootprints +:
              initializeFootprints ++:
              atomicityConstraints ++:
              Vector(translate(body)),
            Vector.empty
          )()

        var vprWhile =
          vpr.While(
            cond = translate(cond),
            invs = invs map (inv => translate(inv.assertion).withSource(inv, overwrite = true)),
            body = vprBody
          )()

        vprWhile = vprWhile.withSource(statement)

        val total = vpr.Seqn(
          Vector(preAtomicityLabel, vprWhile),
          Vector.empty
        )()

        surroundWithSectionComments(statement.statementName, total)

      case PAssign(lhs, rhs) =>
        var vprAssign =
          vpr.LocalVarAssign(
            vpr.LocalVar(lhs.name)(typ = translate(semanticAnalyser.typeOfIdn(lhs))),
            translate(rhs)
          )()

        vprAssign = vprAssign.withSource(statement)

        surroundWithSectionComments(statement.statementName, vprAssign)

      case read: PHeapRead =>
        val vprRead = translate(read)

        surroundWithSectionComments(statement.statementName, vprRead)

      case write: PHeapWrite =>
        val vprWrite = translate(write)

        surroundWithSectionComments(statement.statementName, vprWrite)

      case foldUnfold: PFoldUnfold with PStatement =>
        val (vprPredicateAccess, optConstraints) = translateUseOf(foldUnfold.predicateExp)

        val optVprCheckConstraints =
          optConstraints.map(vpr.Assert(_)().withSource(statement))

        val vprAssignments = extractLogicalVariableBindings(foldUnfold.predicateExp)

        val vprResult =
          foldUnfold match {
            case _: PFold =>
              val vprFold = vpr.Fold(vprPredicateAccess)().withSource(statement)

              optVprCheckConstraints.fold(vprFold: vpr.Stmt)(vprCheck =>
                vpr.Seqn(
                  Vector(vprFold, vprCheck) ++ vprAssignments,
                  Vector.empty
                )()
              )

            case _: PUnfold =>
              val vprUnfold = vpr.Unfold(vprPredicateAccess)().withSource(statement)

              val checkConstraints = optVprCheckConstraints.fold(vprUnfold: vpr.Stmt)(vprCheck =>
                vpr.Seqn(
                  vprAssignments ++ Vector(vprCheck, vprUnfold),
                  Vector.empty
                )()
              )

              val inferInterferenceContext = inferContextAllInstances("recompute interference context after unfold")

              // TODO: generalize context inference after ghost statements
              vpr.Seqn(
                Vector(checkConstraints, inferInterferenceContext),
                Vector.empty
              )()
          }

        val ebt = this.errorBacktranslator // TODO: Should not be necessary!!!!!
        optVprCheckConstraints.foreach(vprCheck =>
          errorBacktranslator.addErrorTransformer {
            case err: vprerr.AssertFailed if err causedBy vprCheck =>
              foldUnfold match {
                case fold: PFold => FoldError(fold, ebt.translate(err.reason))
                case unfold: PUnfold => UnfoldError(unfold, ebt.translate(err.reason))
              }
          })

        surroundWithSectionComments(statement.statementName, vprResult)

      case PInhale(assertion) =>
        val vprInhale =
          vpr.Inhale(translate(assertion))().withSource(statement)

        val vprAssignments = extractLogicalVariableBindings(assertion)

        val vprResult =
          vpr.Seqn(
            vprInhale +: vprAssignments,
            Vector.empty
          )()

        surroundWithSectionComments(statement.statementName, vprResult)

      case PExhale(assertion) =>
        val vprExhale =
          vpr.Exhale(translate(assertion))().withSource(statement)

        val vprAssignments = extractLogicalVariableBindings(assertion)

        val vprResult =
          vpr.Seqn(
            vprAssignments :+ vprExhale,
            Vector.empty
          )()

        surroundWithSectionComments(statement.statementName, vprResult)

      case PAssert(assertion) =>
        val vprAssert =
          vpr.Assert(translate(assertion))().withSource(statement)

        val vprAssignments = extractLogicalVariableBindings(assertion)

        val vprResult =
          vpr.Seqn(
            vprAssert +: vprAssignments,
            Vector.empty
          )()

        surroundWithSectionComments(statement.statementName, vprResult)

      case PAssume(assertion) =>
        var assume = vpr.Inhale(translate(assertion))()

        assume = assume.withSource(statement)

        surroundWithSectionComments(statement.statementName, assume)

      case PHavocVariable(variable) =>
        var vprHavoc = havoc(variable)

        vprHavoc = vprHavoc.withSource(statement)

        surroundWithSectionComments(statement.statementName, vprHavoc)

      case PHavocLocation(location) =>
        val vprAcc =
          vpr.FieldAccessPredicate(
            translate(location),
            vpr.FullPerm()()
          )().withSource(statement)

        val vprExhale = vpr.Exhale(vprAcc)()
        val vprInhale = vpr.Inhale(vprAcc)()

        val vprStatement =
          vpr.Seqn(
            Vector(vprExhale, vprInhale),
            Vector.empty
          )()

        surroundWithSectionComments(statement.statementName, vprStatement)

      case PUseRegionInterpretation(regionPredicate) =>
        val (region, vprInArgs, Seq()) = getAndTranslateRegionPredicateDetails(regionPredicate)
        val vprRegionPredicateAccess = regionPredicateAccess(region, vprInArgs)

        val vprUnfoldRegion = vpr.Unfold(vprRegionPredicateAccess)()



        val collectAllGuardExps = collect[Vector, PGuardExp] { case exp: PGuardExp => exp }
        val guardExps = collectAllGuardExps(region.interpretation)

        val vprGuardPredicateAccesses: Vector[vpr.PredicateAccessPredicate] = {
          guardExps foreach (guardExp => {
            assert(
              region.formalInArgs.exists(_.id.name == guardExp.regionId.id.name),
              "Not yet supported: applying the use-region-interpretation statement to " +
              "a region whose interpretation includes guards whose arguments are not " +
              "directly formal region arguments. " +
              s"In this case: $guardExp from the interpretation of region ${region.id}.")})

          guardExps
            .map(guardExp => guardExp -> semanticAnalyser.entity(guardExp.guard).asInstanceOf[GuardEntity])
            .filter { case (_, guardEntity) => guardEntity.region == region }
            .flatMap { case (guardExp, guardEntity) =>
              val guardDecl = guardEntity.declaration
              guardDecl.modifier match {
                case PUniqueGuard() => Some(translateUseOf(guardExp, guardDecl, region))
                case PDuplicableGuard() => None
            }}
        }

        val vprPermissionConstraintsForUniqueGuards =
          vprGuardPredicateAccesses map (vprGuardAccess => {
            vpr.Inhale(
              vpr.PermLeCmp(
                vpr.CurrentPerm(vprGuardAccess.loc)(),
                vpr.FullPerm()()
              )()
            )()
          })



        val vprFoldRegion = vpr.Fold(vprRegionPredicateAccess)()

        // TODO: generalize interference context inference after ghost code
        val inferInterferenceContext = inferContextAllInstances("infer interference context after use-region interpretation")

        val result =
          vpr.Seqn(
              vprUnfoldRegion +:
              vprPermissionConstraintsForUniqueGuards :+
              vprFoldRegion :+
              inferInterferenceContext,
            Vector.empty
          )().withSource(statement)

        surroundWithSectionComments(statement.statementName, result)

      case PLemmaApplication(call) =>
        val vprMethodStub = translatedProcedureStubs(call.procedure.name)
        val vprArguments = call.arguments map translate
        val vprReturns = call.rhs map (id => translateUseOf(id).asInstanceOf[vpr.LocalVar])

        val vprCall =
          vpr.MethodCall(
            method = vprMethodStub,
            args = vprArguments,
            targets = vprReturns
          )().withSource(statement)

        surroundWithSectionComments(statement.statementName, vprCall)

      case call @ PProcedureCall(procedureId, arguments, rhs) =>
        val callee =
          semanticAnalyser.entity(procedureId).asInstanceOf[ProcedureEntity].declaration

        val vprArguments = arguments map translate
        val vprReturns = rhs map (id => translateUseOf(id).asInstanceOf[vpr.LocalVar])

        val vprInterferenceChecks: vpr.Stmt =
          callee.atomicity match {
            case PNonAtomic() =>
              vpr.Assert(vpr.TrueLit()())()

            case PPrimitiveAtomic() =>
              vpr.Assert(vpr.TrueLit()())()

            case PAbstractAtomic() =>
              // TODO: Inject code comments at various places

//              val vprPreHavocLabel = freshLabel("pre_havoc")

              /* Code that havocks parent regions instead of the current region. See also issue #29. */
              // def generateParentRegionHavockingCode(childRegion: vpr.PredicateAccess): (vpr.Exp, vpr.Seqn) = {
              //   currentlyOpenRegions match {
              //     case Nil =>
              //       (vpr.FalseLit()(), vpr.Seqn(Vector.empty, Vector.empty)())
              //
              //     case (region, regionArguments, vprPreOpenLabel) :: Nil =>
              //       val vprRegionArguments = regionArguments map translate
              //       val vprRegionAccess = regionPredicateAccess(region, vprRegionArguments)
              //
              //       val vprFold = vpr.Fold(vprRegionAccess)()
              //       val vprUnfold = vpr.Unfold(vprRegionAccess)()
              //
              //       val vprPreHavocLabel = freshLabel("pre_havoc")
              //
              //       val havocParentRegion =
              //         havocSingleRegionInstance(region, regionArguments, vprPreHavocLabel, None)
              //
              //       val vprCalleeRegionPerms = vpr.CurrentPerm(childRegion)()
              //
              //       val vprHavocParentRegionCondition =
              //         vpr.PermLtCmp(
              //           vpr.LabelledOld(
              //             vprCalleeRegionPerms,
              //             vprPreOpenLabel.name
              //           )(),
              //           vprCalleeRegionPerms
              //         )()
              //
              //       val vprResult =
              //         vpr.Seqn(
              //           Vector(
              //             vprFold,
              //             vprPreHavocLabel,
              //             havocParentRegion.asSeqn,
              //             vprUnfold),
              //           Vector.empty
              //         )()
              //
              //       (vprHavocParentRegionCondition, vprResult)
              //
              //     case _ =>
              //       sys.error("Multiple nested open regions are not yet supported")
              //   }
              // }

              val vprCheckInterferences =
                callee.inters.map (inter => {
                  val regionPredicate = semanticAnalyser.usedWithRegionPredicate(inter.region)
                  val (region, regionArguments, _) = getRegionPredicateDetails(regionPredicate)

                  /* TODO: We should have a more systematic - and also easily reusable - way of
                   *       instantiating formals with actual during translations
                   */

                  val subst: Map[String, vpr.Exp] =
                    callee.formalArgs
                      .zip(vprArguments)
                      .map{case (formal, actual) => formal.id.name -> actual}(breakOut)

                  val vprRegionArguments =
                    regionArguments map (argument => {
                      translateWith(argument) {
                        case idn: PIdnExp if subst.contains(idn.id.name) => subst(idn.id.name)
                        case idn: PIdnUse if subst.contains(idn.name) => subst(idn.name)
                      }
                    })

                  /* See issue #29 */
                  // val vprRegionPredicateAccess =
                  //   regionPredicateAccess(region, vprRegionArguments)
                  //
                  // val havocCalleeRegion =
                  //   havocSingleRegionInstance(region, regionArguments, vprPreHavocLabel, None)
                  //
                  // val (vprHavocParentRegionCondition, vprHavocParentRegion) =
                  //   generateParentRegionHavockingCode(vprRegionPredicateAccess.loc)
                  //
                  // val vprHavocRegion =
                  //   vpr.If(
                  //     vprHavocParentRegionCondition,
                  //     vprHavocParentRegion,
                  //     vpr.Seqn(
                  //       Vector(havocCalleeRegion.exhale, havocCalleeRegion.inhale),
                  //       Vector.empty
                  //     )()
                  //   )()
//                  val vprHavocRegion = stabilizeSingleInstances("call interference check", (region, vprRegionArguments))
////                    prependComment(
////                      s"Stabilising region $regionPredicate",
////                      stabilizeRegionInstance(region, vprRegionArguments, vprPreHavocLabel))
//
//                  val vprCurrentState =
//                    vpr.FuncApp(
//                      regionStateFunction(region),
//                      vprRegionArguments
//                    )()

                  val vprInterferenceSet = {
                    val vprFormalsToActuals =
                      callee.formalArgs.map(translate(_).localVar).zip(vprArguments).toMap

                    translateWith(inter.set){
                      case exp@ PIdnExp(id) => {

                        evaluateInterferenceContext(id, exp)
                      }
                    }.replace(vprFormalsToActuals)
                  }

                  val varName = "$_m"
                  val typ = vprInterferenceSet.typ match { case vpr.SetType(e) => e }
                  val decl = vpr.LocalVarDecl("$_m", typ)()
                  val variable = decl.localVar

                  val lhs = vpr.AnySetContains(
                    variable,
                    interferenceSetFunctions.application(region, vprRegionArguments)
                  )()

                  val rhs = vpr.AnySetContains(
                    variable,
                    vprInterferenceSet
                  )()

                  val vprCheckStateUnchanged =
                    vpr.Assert(
                      vpr.Forall(
                        variables = Vector(decl),
                        triggers = Vector(vpr.Trigger(Vector(rhs))()),
                        vpr.Implies(lhs, rhs)()
                      )()
                    )()

                  errorBacktranslator.addErrorTransformer {
//                    case e @ vprerr.PreconditionInAppFalse(_, _: vprrea.InsufficientPermission, _)
//                         if e causedBy vprCurrentState =>
//
//                      PreconditionError(call, InsufficientRegionPermissionError(regionPredicate))

                    case e @ vprerr.AssertFailed(node, reason, _)
                         if (e causedBy vprCheckStateUnchanged) &&
                            (reason causedBy vprCheckStateUnchanged.exp) =>

                      PreconditionError(call, InterferenceError(inter))
                  }

//                  vpr.Seqn(
//                    Vector(
//                      vprHavocRegion,
//                      /* See issue #29 */
//                      // havocCalleeRegion.constrainStateViaGuards,
//                      // havocCalleeRegion.constrainStateViaAtomicityContext,
//                      vprCheckStateUnchanged),
//                    Vector.empty
//                  )()
                  vprCheckStateUnchanged
                })

//              val vprNonDetChoiceVariable = declareFreshVariable(PBoolType(), "nondet").localVar
//
//              val vprHavocNonDetChoiceVariable = havoc(vprNonDetChoiceVariable)
//
//              val vprNonDetCheckBranch =
//                vpr.If(
//                  vprNonDetChoiceVariable,
//                  vpr.Seqn(
//                    vprPreHavocLabel +:
//                    vprCheckInterferences :+
//                    vpr.Inhale(vpr.FalseLit()())(),
//                    Vector.empty
//                  )(),
//                  vpr.Seqn(Vector.empty, Vector.empty)()
//                )()
//
//              vpr.Seqn(
//                Vector(vprHavocNonDetChoiceVariable, vprNonDetCheckBranch),
//                Vector.empty
//              )()

              vpr.Seqn(vprCheckInterferences, Vector.empty)()
          }

        val vprCall = {
          val vprStub = translatedProcedureStubs(procedureId.name)

          val vprPreCallLabel = freshLabel("pre_call")

          val vprPre =
            vpr.utility.Expressions.instantiateVariables(
              viper.silicon.utils.ast.BigAnd(vprStub.pres),
              vprStub.formalArgs,
              vprArguments)

          val vprPost =
            vpr.utility.Expressions.instantiateVariables(
              viper.silicon.utils.ast.BigAnd(vprStub.posts),
              vprStub.formalArgs ++ vprStub.formalReturns,
              vprArguments ++ vprReturns
            ).transform(
              {
                case old: vpr.Old =>
                  vpr.LabelledOld(old.exp, vprPreCallLabel.name)(old.pos, old.info, old.errT)
              },
              vpr.utility.Rewriter.Traverse.TopDown)

          val vprExhalePres = vpr.Exhale(vprPre)()

          val ebt = this.errorBacktranslator // TODO: Should not be necessary!!!!!
          errorBacktranslator.addErrorTransformer {
            case e: vprerr.ExhaleFailed if e causedBy vprExhalePres =>
              PreconditionError(call, ebt.translate(e.reason))
          }

          val stabilizeFrameRegions =
            stabilizeAllInstances(s"before ${call.statementName}@${call.lineColumnPosition}")

          val vprHavocTargets = vprReturns map havoc

          val vprInhalePosts = vpr.Inhale(vprPost)()

          vpr.Seqn(
            vprPreCallLabel +:
            vprExhalePres +:
            stabilizeFrameRegions +:
            vprHavocTargets :+
            vprInhalePosts,
            Vector.empty
          )()
        }

        val result =
          vpr.Seqn(
            Vector(vprInterferenceChecks, vprCall),
            Vector.empty
          )()

        surroundWithSectionComments(statement.statementName, result)

      case stmt: PMakeAtomic => translate(stmt)
      case stmt: PUpdateRegion => translate(stmt)
      case stmt: PUseAtomic => translate(stmt)
      case stmt: POpenRegion => translate(stmt)
    }
  }

  def translate(expression: PExpression): vpr.Exp =
    translateWith(expression)(PartialFunction.empty)

  def translateWith(expression: PExpression)
                   (customTranslationScheme: PartialFunction[PExpression, vpr.Exp])
                    : vpr.Exp = {

    def go(expression: PExpression) = translateWith(expression)(customTranslationScheme)

    type VprExpConstructor =
      (vpr.Exp, vpr.Exp) => (vpr.Position, vpr.Info, vpr.ErrorTrafo) => vpr.Exp

    type PolymorphicOperatorTranslationScheme =
      Map[Class[_ <: PBinOp], Map[PType, VprExpConstructor]]

    val polymorphicOperatorTranslationScheme: PolymorphicOperatorTranslationScheme =
      Map(
        classOf[PAdd]     -> Map(PIntType() -> vpr.Add.apply,   PFracType() -> vpr.PermAdd.apply),
        classOf[PSub]     -> Map(PIntType() -> vpr.Sub.apply,   PFracType() -> vpr.PermSub.apply),
        classOf[PMul]     -> Map(PIntType() -> vpr.Mul.apply,   PFracType() -> vpr.PermMul.apply),
        classOf[PLess]    -> Map(PIntType() -> vpr.LtCmp.apply, PFracType() -> vpr.PermLtCmp.apply),
        classOf[PAtMost]  -> Map(PIntType() -> vpr.LeCmp.apply, PFracType() -> vpr.PermLeCmp.apply),
        classOf[PGreater] -> Map(PIntType() -> vpr.GtCmp.apply, PFracType() -> vpr.PermGtCmp.apply),
        classOf[PAtLeast] -> Map(PIntType() -> vpr.GeCmp.apply, PFracType() -> vpr.PermGeCmp.apply))

    def transPoly(op: PBinOp): vpr.Exp = {
      assert(
        semanticAnalyser.typ(op.left) == semanticAnalyser.typ(op.right),
          s"Expected both operands of ${op.formatForUsers} to have the same type, but got "
        + s"${semanticAnalyser.typ(op.left)} and ${semanticAnalyser.typ(op.right)}")

      val vprLeft = go(op.left)
      val vprRight = go(op.right)

      val constructor =
        polymorphicOperatorTranslationScheme(op.getClass)(semanticAnalyser.typ(op.left))

      val vprExp =
        constructor(vprLeft, vprRight)(vpr.NoPosition, vpr.NoInfo, vpr.NoTrafos)

      vprExp.withSource(expression)
    }

    val defaultTranslationScheme: PartialFunction[PExpression, vpr.Exp] = {
      case PTrueLit() => vpr.TrueLit()().withSource(expression)
      case PFalseLit() => vpr.FalseLit()().withSource(expression)
      case PNullLit() => vpr.NullLit()().withSource(expression)
      case PIntLit(n) => vpr.IntLit(n)().withSource(expression)
      case PFullPerm() => vpr.FullPerm()().withSource(expression)
      case PNoPerm() => vpr.NoPerm()().withSource(expression)
      case PEquals(left, right) => vpr.EqCmp(go(left), go(right))().withSource(expression)
      case PAnd(left, right) => vpr.And(go(left), go(right))().withSource(expression)
      case POr(left, right) => vpr.Or(go(left), go(right))().withSource(expression)
      case PNot(operand) => vpr.Not(go(operand))().withSource(expression)
      case op: PLess => transPoly(op)
      case op: PAtMost => transPoly(op)
      case op: PGreater => transPoly(op)
      case op: PAtLeast => transPoly(op)
      case op: PAdd => transPoly(op)
      case op: PSub => transPoly(op)
      case op: PMul => transPoly(op)

      case PFrac(left, right) =>
        if (semanticAnalyser.typ(left) == PFracType()) {
          assert(semanticAnalyser.typ(right) == PIntType())

          vpr.PermDiv(go(left), go(right))().withSource(expression)
        } else {
          assert(semanticAnalyser.typ(left) == PIntType())
          assert(semanticAnalyser.typ(right) == PIntType())

          vpr.FractionalPerm(go(left), go(right))().withSource(expression)
        }

      case PMod(left, right) => vpr.Mod(go(left), go(right))().withSource(expression)
      case PDiv(left, right) => vpr.Div(go(left), go(right))().withSource(expression)
      case PConditional(cond, thn, els) => vpr.CondExp(go(cond), go(thn), go(els))().withSource(expression)

      /* TODO: Unify cases for PExplicitSet and PExplicitSeq */

      case PExplicitSet(elements, _) =>
        if (elements.isEmpty) {
          val elemType = semanticAnalyser.typ(expression).asInstanceOf[PSetType].elementType
          vpr.EmptySet(translate(elemType))().withSource(expression)
        } else {
          vpr.ExplicitSet(elements map go)().withSource(expression)
        }

      case PExplicitSeq(elements, _) =>
        if (elements.isEmpty) {
          val elemType = semanticAnalyser.typ(expression).asInstanceOf[PSeqType].elementType
          vpr.EmptySeq(translate(elemType))().withSource(expression)
        } else {
          vpr.ExplicitSeq(elements map go)().withSource(expression)
        }

      case PSetContains(element, set) => vpr.AnySetContains(go(element), go(set))().withSource(expression)
      case PSetUnion(left, right) => vpr.AnySetUnion(go(left), go(right))().withSource(expression)
      case PSeqSize(seq) => vpr.SeqLength(go(seq))().withSource(expression)
      case PSeqHead(seq) => vpr.SeqIndex(go(seq), vpr.IntLit(0)())().withSource(expression)
      case PSeqTail(seq) => vpr.SeqDrop(go(seq), vpr.IntLit(1)())().withSource(expression)
      case pairExp: PPairExp => translatePairExpression(pairExp)
      case mapExp: PMapExp => translateMapExpression(mapExp)
      case PIntSet() => preamble.sets.int.withSource(expression)
      case PNatSet() => preamble.sets.nat.withSource(expression)
      case PIdnExp(id) => translateUseOf(id).withSource(expression, overwrite = true)

      case comprehension: PSetComprehension =>
        val vprFunction = recordedSetComprehensions(comprehension)
        val freeVariables = semanticAnalyser.freeVariables(comprehension)

        assert(
          vprFunction.formalArgs.lengthCompare(freeVariables.size) == 0,
          s"Cardinality mismatch: ${vprFunction.formalArgs.length} vs ${freeVariables.size}")

        vpr.FuncApp(
          vprFunction,
          freeVariables.map(translateUseOf)(breakOut)
        )()

      case pointsTo: PPointsTo => translate(pointsTo)
      case guard: PGuardExp => translate(guard)

      case PUnfolding(predicate, body) =>
        /* TODO: Rather brittle, improve! */
        val vprPredicate =
          go(predicate) match {
            case vprPredicate: vpr.PredicateAccessPredicate => vprPredicate
            case vpr.And(vprPredicate: vpr.PredicateAccessPredicate, _: vpr.Exp) => vprPredicate
            case other => sys.error(s"Unexpectedly found $other as the translation of $predicate")
          }

        val vprBody = go(body)

        vpr.Unfolding(vprPredicate, vprBody)().withSource(expression)

      case predicateExp: PPredicateExp =>
        val (vprPredicateAccess, optVprConstraints) = translateUseOf(predicateExp)

        optVprConstraints match {
          case Some(vprConstraint) =>
            vpr.And(vprPredicateAccess, vprConstraint)().withSource(predicateExp)
          case None =>
            vprPredicateAccess
        }

      case PDiamond(regionId) =>
        diamondAccess(translateUseOf(regionId))

      case PRegionUpdateWitness(regionId, from, to) =>
        val region = semanticAnalyser.usedWithRegion(regionId)
        val vprRegionType = semanticAnalyser.typ(region.state)
        val vprRegionReceiver = translateUseOf(regionId)
        val vprFrom = go(from)
        val vprTo = go(to)

        vpr.And(
          vpr.And(
            stepFromAccess(vprRegionReceiver, vprRegionType),
            vpr.EqCmp(
              stepFromLocation(vprRegionReceiver, vprRegionType),
              vprFrom
            )()
          )(),
          vpr.And(
            stepToAccess(vprRegionReceiver, vprRegionType),
            vpr.EqCmp(
              stepToLocation(vprRegionReceiver, vprRegionType),
              vprTo
            )()
          )()
        )()

      case binder: PAnonymousBinder =>
        sys.error(
          s"Wildcard arguments ($binder) are not yet supported in this position: " +
          s"${binder.position}. In particular, wildcards are not supported in in-argument position, e.g. " +
           "of region assertions.")

      case binder: PNamedBinder =>
        sys.error(
          s"Logical variable binders ($binder) are not yet supported in this position: ${binder.position}. " +
          "In particular, wildcards are not supported in in-argument position, e.g. of region assertions.")
    }

    customTranslationScheme.applyOrElse(expression, defaultTranslationScheme)
  }

  /* TODO: Unify translatePairExpression and translateMapExpression */

  private def translatePairExpression(expression: PPairExp): vpr.Exp = {
    def apply(pairTypedExpression: PExpression,
              pairFunction: vpr.DomainFunc,
              arguments: Vector[vpr.Exp])
             : vpr.DomainFuncApp = {

      val (elementType1, elementType2) =
        semanticAnalyser.typ(pairTypedExpression) match {
          case pairType: PPairType =>
            (translate(pairType.elementType1), translate(pairType.elementType2))
          case other =>
            sys.error(
              s"Expected $expression to be of type pair, but got $other " +
                  s"(at ${expression.lineColumnPosition})")
        }

      val typeVarMap = preamble.pairs.typeVarMap(elementType1, elementType2)

      vpr.DomainFuncApp(
        func = pairFunction,
        args = arguments,
        typVarMap = typeVarMap
      )().withSource(expression)
    }

    expression match {
      case pair @ PExplicitPair(element1, element2, _) =>
        apply(pair, preamble.pairs.pair, Vector(translate(element1), translate(element2)))

      case PPairFirst(pair) =>
        apply(pair, preamble.pairs.first, Vector(translate(pair)))

      case PPairSecond(pair) =>
        apply(pair, preamble.pairs.second, Vector(translate(pair)))
    }
  }

  private def translateMapExpression(expression: PMapExp): vpr.Exp = {
    def apply(mapTypedExpression: PExpression,
              mapFunction: vpr.DomainFunc,
              arguments: Vector[vpr.Exp])
             : vpr.DomainFuncApp = {

      val (keyType, valueType) =
        semanticAnalyser.typ(mapTypedExpression) match {
          case mapType: PMapType =>
            (translate(mapType.keyType), translate(mapType.valueType))
          case other =>
            sys.error(
              s"Expected $expression to be of type map, but got $other " +
                  s"(at ${expression.lineColumnPosition})")
        }

      val typeVarMap = preamble.maps.typeVarMap(keyType, valueType)

      vpr.DomainFuncApp(
        func = mapFunction,
        args = arguments,
        typVarMap = typeVarMap
      )().withSource(expression)
    }

    expression match {
      case explicitMap @ PExplicitMap(elements, _) =>
        val emp = apply(explicitMap, preamble.maps.empty, Vector.empty)

        elements.foldLeft(emp){ case (map, (key, value)) =>
          vpr.DomainFuncApp(
            func = preamble.maps.build,
            args = Vector(map, translate(key), translate(value)),
            typVarMap = emp.typVarMap
          )().withSource(expression)
        }

      case union @ PMapUnion(map1, map2) =>
        apply(union, preamble.maps.union, Vector(translate(map1), translate(map2)))

      case PMapDisjoint(map1, map2) =>
        apply(map1, preamble.maps.disjoint, Vector(translate(map1), translate(map2)))

      case PMapKeys(map) =>
        apply(map, preamble.maps.keys, Vector(translate(map)))

      case PMapLookup(map, key) =>
        apply(map, preamble.maps.lookup, Vector(translate(map), translate(key)))
    }
  }

  def translateUseOf(id: PIdnNode): vpr.Exp = {
    semanticAnalyser.entity(id) match {
      case entity: LogicalVariableEntity =>
        translateUseOf(id, entity.declaration)
      case FormalArgumentEntity(decl) =>
        vpr.LocalVar(id.name)(typ = translate(decl.typ)).withSource(id)
      case FormalReturnEntity(decl) =>
        vpr.LocalVar(id.name)(typ = translate(decl.typ)).withSource(id)
      case LocalVariableEntity(decl) =>
        vpr.LocalVar(id.name)(typ = translate(decl.typ)).withSource(id)
    }
  }

  def translateUseOf(id: PIdnNode, binder: PNamedBinder): vpr.Exp = {
    /* TODO: Use localVariableDeclaration(binder: PNamedBinder): vpr.LocalVarDecl to translate
     *       first and second case?
     */
    semanticAnalyser.boundBy(binder) match {
      case PSetComprehension(`binder`, _, _) =>
        val vprType = translate(semanticAnalyser.typeOfLogicalVariable(binder))

        vpr.LocalVar(id.name)(typ = vprType)

      case action: PAction if action.binds(binder) =>
        val vprType = translate(semanticAnalyser.typeOfLogicalVariable(binder))

        vpr.LocalVar(id.name)(typ = vprType).withSource(id)

      case PPointsTo(_, `binder`) => translateAsHeapAccess(id, binder)
      case PPredicateExp(_, args) if args.exists(_ eq binder) => translateAsHeapAccess(id, binder)
      case PInterferenceClause(`binder`, _, _) => translateAsHeapAccess(id, binder)
    }
  }

  def translateUseOf(predicateExp: PPredicateExp): (vpr.PredicateAccessPredicate, Option[vpr.Exp]) = {
    val predicateEntity = semanticAnalyser.entity(predicateExp.predicate)

    val (vprInArgs, optVprOutArgConstraints) =
      predicateEntity match {
        case _: PredicateEntity =>
          (predicateExp.arguments map translate, None)

        case _: RegionEntity =>
          val (_, vprInArgs, vprOutArgConstraints) = getAndTranslateRegionPredicateDetails(predicateExp)

          (vprInArgs, Some(vprOutArgConstraints))
      }

    val vprPredicate =
      vpr.PredicateAccess(
        args = vprInArgs,
        predicateName = predicateExp.predicate.name
      )().withSource(predicateExp)

    val vprPredicateAccess =
      vpr.PredicateAccessPredicate(
        vprPredicate,
        vpr.FullPerm()()
      )().withSource(predicateExp)

    predicateEntity match {
      case _: PredicateEntity =>
        /* TODO: Add error backtranslation handlers? */

        (vprPredicateAccess, None)

      case _: RegionEntity =>
        val vprStateConstraint =
          viper.silicon.utils.ast.BigAnd(optVprOutArgConstraints.get).withSource(predicateExp)

        errorBacktranslator.addReasonTransformer {
          case e: vprrea.InsufficientPermission if e causedBy vprPredicate =>
            InsufficientRegionPermissionError(predicateExp)
          case e: vprrea.AssertionFalse if e causedBy vprStateConstraint => /* TODO: Fine-grained enough (per conjunct)? */
            RegionStateError(predicateExp)
        }

        (vprPredicateAccess, Some(vprStateConstraint))
    }
  }

  def translate(declaration: PLocalVariableDecl): vpr.LocalVarDecl =
    vpr.LocalVarDecl(declaration.id.name, translate(declaration.typ))()

  def translate(typ: PType): vpr.Type = typ match {
    case PIntType() => vpr.Int
    case PBoolType() => vpr.Bool
    case PFracType() => vpr.Perm
    case PSetType(elementType) => vpr.SetType(translate(elementType))
    case PSeqType(elementType) => vpr.SeqType(translate(elementType))

    case PPairType(elementType1, elementType2) =>
      vpr.DomainType(
        preamble.pairs.domain,
        preamble.pairs.typeVarMap(translate(elementType1), translate(elementType2)))

    case PMapType(elementType1, elementType2) =>
      vpr.DomainType(
        preamble.maps.domain,
        preamble.maps.typeVarMap(translate(elementType1), translate(elementType2)))

    case PRefType(_) => vpr.Ref
    case PRegionIdType() => vpr.Ref

    case unsupported@(_: PUnknownType) => sys.error(s"Cannot translate type '$unsupported'")
  }
}
