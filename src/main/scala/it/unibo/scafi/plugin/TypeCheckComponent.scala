package it.unibo.scafi.plugin

import it.unibo.scafi.definition.{AggregateFunction, AggregateType, ArrowType, F, L}

import scala.tools.nsc.Phase

/**
  * check if, in the aggregate program (or in constructor definition) certain
  * properties are satisfied. Examples of properties are:
  *   - if not allowed inside aggregate main
  *   - type checking in the aggregate constructor ( nbr(nbr(x)) mustn't compile
  */
class TypeCheckComponent(context : ComponentContext) extends AbstractComponent(context, TypeCheckComponent) {
  import TypeCheckComponent._
  import global._
  override def newPhase(prev: Phase): Phase = new TypeCheckPhase(prev)
  /**
    * the phases used to check the type checking correctness.
    * @param prev the previous phase in the compilation pipeline
    */
  class TypeCheckPhase(prev: Phase) extends StdPhase(prev) {
    private def extractAggregateProgram(tree : Tree) : Option[Tree] = Some(tree).filter(extendsFromType(_, context.aggregateProgram))

    private def extractAggregateFunction(tree : Tree) : Option[AggregateFunction] =  context.extractAggFunctionFromTree(tree)
    //here the magic happens, verify the correctness of the programs
    override def apply(unit: CompilationUnit): Unit = {
      global.inform(phaseName)
      def searchMainBody(tree : Tree) : Option[Tree] = extractAggregateMain(tree) match {
        case None => tree.children.map(searchMainBody).collectFirst {
          case Some(mainBody) => mainBody
        }
        case Some(defMain) => Some(defMain.rhs)
      }

      //extract all aggregate main, and check the properties
      search(unit.body)(extractAggregateProgram).map(searchMainBody).collect {
        case Some(mainTree) => mainTree
      } foreach {
        evalAggregateMain  //here starts program evaluation
      }
    }
    private def evalAggregateMain(tree : Tree) : Unit = {
      //in this def, there are all the checking in the programs
      ifPresenceCheck(tree)
      aggregateFunctionCorrectness(tree)
    }

    private def ifPresenceCheck(tree : Tree): Unit = tree match {
      case If(_,_,_) =>
        warning(tree.pos, ifInfoString)
        tree.children.foreach(ifPresenceCheck)
      case _ =>
        tree.children.foreach(ifPresenceCheck)
    }

    private def aggregateFunctionCorrectness(tree : Tree) : Unit = (tree, extractAggregateFunction(tree)) match {
      case (apply : Apply, Some(ag)) => checkAggFunCorrectness(ag, apply)
      case _ => tree.children.foreach(aggregateFunctionCorrectness)
    }

    private def checkAggFunCorrectness(function : AggregateFunction, applyTree : Apply) = {
      val blockWithArgTree = function.argsReversed
          .zipWithIndex
          .map { case (block, i) => block -> uncurry(applyTree, i)}
          .collect { case (block, _ @ Some(uncurried)) => block -> uncurried.args }

      blockWithArgTree
        .flatMap { case (block, argsTree) => block.zip(argsTree) }
        .foreach { case (aggType, argTree) => checkArgsCorrectness(function, aggType, argTree)}
    }
    //TODO Think how you can add index in error.
    private def checkArgsCorrectness(fun : AggregateFunction, aggregateType : AggregateType, argDefinition : Tree) = {
      def checkTypeConsistency(): Unit = aggregateType match { //todo give a bettername
          case F if !isFieldPresent(argDefinition) => error(aggregateTypeError(fun, F, L), argDefinition.pos)
          case L if isFieldPresent(argDefinition) => error(aggregateTypeError(fun, L, F), argDefinition.pos)
          case ArrowType(returns, args) => //TODO how to manage arrow type arguments?
          case _ =>
      }
      context.extractArgType(argDefinition.symbol) match {
        case Some(tpe) if (tpe != aggregateType) => error(aggregateTypeError(fun, aggregateType, tpe))
        case _ => checkTypeConsistency()
      }
      argDefinition.children.foreach(aggregateFunctionCorrectness)
    }

    private def isFieldPresent(tree : Tree) : Boolean = {
      tree.children.map(extractAggregateFunction)
        .collect { case Some(aggFun) => aggFun }
        .exists(_.returns == F)
    }
  }
  override val descriptor: ComponentDescriptor = TypeCheckComponent
}

object TypeCheckComponent extends ComponentDescriptor {
  override def name: String = "typecheck"

  override val runsAfter: List[String] = List(DiscoverComponent.name)

  override val runsBefore: List[String] = List("uncurry")

  override def apply()(implicit c: ComponentContext): TypeCheckComponent = new TypeCheckComponent(c)

  def aggregateTypeError(fun : AggregateFunction, expected : AggregateType, found : AggregateType) : String = {
    s"$fun wrong type: expected $expected but found $found"
  }

  val ifInfoString : String = "if not allowed in aggregate main"
}