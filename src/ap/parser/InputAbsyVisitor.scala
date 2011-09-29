/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2009-2011 Philipp Ruemmer <ph_r@gmx.net>
 *
 * Princess is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princess is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Princess.  If not, see <http://www.gnu.org/licenses/>.
 */

package ap.parser;

import ap.terfor.ConstantTerm
import ap.terfor.conjunctions.Quantifier
import ap.util.{Debug, Logic, PlainRange, Seqs}

import scala.collection.mutable.{ArrayStack => Stack, ArrayBuffer}


object CollectingVisitor {
  private val AC = Debug.AC_INPUT_ABSY
}


/**
 * Visitor schema that traverses an expression in depth-first left-first order.
 * For each node, the method <code>preVisit</code> is called when descending
 * and the method <code>postVisit</code> when returning. The visitor works
 * with iteration (not recursion) and is able to deal also with large
 * expressions
 */
abstract class CollectingVisitor[A, R] {

  abstract class PreVisitResult  

  /**
   * Use the same argument for the direct sub-expressions as for this expression
   */
  case object KeepArg extends PreVisitResult
  
  /**
   * Call <code>preVisit</code> again with a different expression and argument
   */
  case class TryAgain(newT : IExpression, newArg : A) extends PreVisitResult
  
  /**
   * Use <code>arg</code> for each of the direct sub-expressions
   */
  case class UniSubArgs(arg : A) extends PreVisitResult
  
  /**
   * Specify the arguments to use for the individual sub-expressions
   */
  case class SubArgs(args : Seq[A]) extends PreVisitResult
  
  /**
   * Skip the call to <code>postVisit</code> and do not visit any of the
   * sub-expressions. Instead, directly return <code>res</code> as result
   */
  case class ShortCutResult(res : R) extends PreVisitResult
  
  def preVisit(t : IExpression, arg : A) : PreVisitResult = KeepArg
  def postVisit(t : IExpression, arg : A, subres : Seq[R]) : R
  
  def visit(expr : IExpression, arg : A) : R = {
    val toVisit = new Stack[IExpression]
    val argsToVisit = new Stack[A]
    val results = new Stack[R]
    
    toVisit push expr
    argsToVisit push arg
    
    while (!toVisit.isEmpty) toVisit.pop match {
      case PostVisit(expr, arg) => {
        var subRes : List[R] = List()
        for (_ <- PlainRange(expr.length)) subRes = results.pop :: subRes
        results push postVisit(expr, arg, subRes)
      }
      
      case expr => {
        val arg = argsToVisit.pop

        preVisit(expr, arg) match {
          case ShortCutResult(res) =>
            // directly push the result, skip the call to postVisit and the
            // recursive calls
            results push res
          
          case TryAgain(newT, newArg) => {
            toVisit push newT
            argsToVisit push newArg
          }
            
          case argModifier => 
            if (expr.length > 0) {
              // recurse
          
              toVisit push PostVisit(expr, arg)
              for (i <- (expr.length - 1) to 0 by -1) toVisit push expr(i)
        
              argModifier match {
                case KeepArg =>
                  for (_ <- PlainRange(expr.length)) argsToVisit push arg
                case UniSubArgs(subArg) =>
                  for (_ <- PlainRange(expr.length)) argsToVisit push subArg
                case SubArgs(subArgs) => {
                  //-BEGIN-ASSERTION-///////////////////////////////////////////
                  Debug.assertInt(CollectingVisitor.AC, subArgs.length == expr.length)
                  //-END-ASSERTION-/////////////////////////////////////////////
                  for (i <- (expr.length - 1) to 0 by -1) argsToVisit push subArgs(i)
                }
              }
          
            } else {
              // otherwise, we can directly call the postVisit method
          
              results push postVisit(expr, arg, List())
            }
        }
      }
    }
          
    //-BEGIN-ASSERTION-/////////////////////////////////////////////////////////
    Debug.assertInt(CollectingVisitor.AC, results.length == 1)
    //-END-ASSERTION-///////////////////////////////////////////////////////////
    results.pop
  }
  
  private case class PostVisit(expr : IExpression, arg : A)
                     extends IExpression
  
}

////////////////////////////////////////////////////////////////////////////////

object VariableShiftVisitor {
  private val AC = Debug.AC_INPUT_ABSY
  
  def apply(t : IExpression, offset : Int, shift : Int) : IExpression =
    if (shift == 0)
      t
    else
      new VariableShiftVisitor(offset, shift).visit(t, 0)
  
  def apply(t : IFormula, offset : Int, shift : Int) : IFormula =
    apply(t.asInstanceOf[IExpression], offset, shift).asInstanceOf[IFormula]
  def apply(t : ITerm, offset : Int, shift : Int) : ITerm =
    apply(t.asInstanceOf[IExpression], offset, shift).asInstanceOf[ITerm]
}

class VariableShiftVisitor(offset : Int, shift : Int)
      extends CollectingVisitor[Int, IExpression] {
  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(VariableShiftVisitor.AC, offset >= 0 && offset + shift >= 0)
  //-END-ASSERTION-/////////////////////////////////////////////////////////////     

  override def preVisit(t : IExpression, quantifierNum : Int) : PreVisitResult =
    t match {
      case _ : IQuantified | _ : IEpsilon => UniSubArgs(quantifierNum + 1)
      case _ => KeepArg
    }
  def postVisit(t : IExpression, quantifierNum : Int,
                subres : Seq[IExpression]) : IExpression =
    t match {
      case IVariable(i) =>
        if (i < offset + quantifierNum) t else IVariable(i + shift)
      case _ =>
        t update subres
    }
}

////////////////////////////////////////////////////////////////////////////////

/**
 * More general visitor for renaming variables. The argument of the visitor
 * methods is a pair <code>(List[Int], Int)</code> that describes how each
 * variable should be shifted: <code>(List(0, 2, -1), 1)</code> specifies that
 * variable 0 stays the same, variable 1 is increased by 2 (renamed to 3),
 * variable 2 is renamed to 1, and all other variables n are renamed to n+1.
 */
object VariablePermVisitor extends CollectingVisitor[IVarShift, IExpression] {

  def apply(t : IExpression, shifts : IVarShift) : IExpression =
    this.visit(t, shifts)

  def apply(t : IFormula, shifts : IVarShift) : IFormula =
    apply(t.asInstanceOf[IExpression], shifts).asInstanceOf[IFormula]
  def apply(t : ITerm, shifts : IVarShift) : ITerm =
    apply(t.asInstanceOf[IExpression], shifts).asInstanceOf[ITerm]

  override def preVisit(t : IExpression, shifts : IVarShift) : PreVisitResult =
    t match {
      case _ : IQuantified | _ : IEpsilon => UniSubArgs(shifts push 0)
      case _ => KeepArg
    }

  def postVisit(t : IExpression, shifts : IVarShift,
                subres : Seq[IExpression]) : IExpression =
    t match {
      case t : IVariable => shifts(t)
      case _ => t update subres
    }
}

object IVarShift {
  private val AC = Debug.AC_INPUT_ABSY
  
  def apply(mapping : Map[IVariable, IVariable],
            defaultShift : Int) : IVarShift = {
    val maxIndex = Seqs.max(for (IVariable(i) <- mapping.keysIterator) yield i)
    val prefix = (for (i <- 0 to (maxIndex + 1))
                  yield (mapping get IVariable(i)) match {
                    case Some(IVariable(j)) => j - i
                    case None => defaultShift
                  }).toList
    IVarShift(prefix, defaultShift)
  }
}

case class IVarShift(prefix : List[Int], defaultShift : Int) {
  
  lazy val length = prefix.length
  
  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
  Debug.assertCtor(IVarShift.AC,
                   defaultShift + length >= 0 &&
                   (prefix.iterator.zipWithIndex forall {case (i, j) => i + j >= 0}))
  //-END-ASSERTION-/////////////////////////////////////////////////////////////

  def push(n : Int) = IVarShift(n :: prefix, defaultShift)
  
  def pop = {
    //-BEGIN-ASSERTION-/////////////////////////////////////////////////////////
    Debug.assertPre(IVarShift.AC, !prefix.isEmpty)
    //-END-ASSERTION-///////////////////////////////////////////////////////////
    IVarShift(prefix.tail, defaultShift)
  }
  
  def compose(that : IVarShift) : IVarShift = {
    val newPrefix = new scala.collection.mutable.ArrayBuffer[Int]
    for ((o, i) <- that.prefix.iterator.zipWithIndex)
      newPrefix += (apply(i + o) - i)
    for (i <- that.length until (this.length - that.defaultShift))
      newPrefix += (apply(i + that.defaultShift) - i)
    IVarShift(newPrefix.toList, this.defaultShift + that.defaultShift)
  }
  
  def apply(i : Int) : Int = {
    //-BEGIN-ASSERTION-/////////////////////////////////////////////////////////
    Debug.assertPre(IVarShift.AC, i >= 0)
    //-END-ASSERTION-///////////////////////////////////////////////////////////
    i + (if (i < length) prefix(i) else defaultShift)
  }
  def apply(v : IVariable) : IVariable = {
    val newIndex = apply(v.index)
    if (newIndex == v.index) v else IVariable(newIndex)
  }
}

////////////////////////////////////////////////////////////////////////////////

/**
 * Substitute some of the constants in an expression with arbitrary terms
 */
object ConstantSubstVisitor
       extends CollectingVisitor[Map[ConstantTerm, ITerm], IExpression] {
  import IExpression.i
         
  def apply(t : IExpression, subst : Map[ConstantTerm, ITerm]) : IExpression =
    ConstantSubstVisitor.visit(t, subst)
  def apply(t : ITerm, subst : Map[ConstantTerm, ITerm]) : ITerm =
    apply(t.asInstanceOf[IExpression], subst).asInstanceOf[ITerm]
  def apply(t : IFormula, subst : Map[ConstantTerm, ITerm]) : IFormula =
    apply(t.asInstanceOf[IExpression], subst).asInstanceOf[IFormula]

  def rename(t : ITerm, subst : Map[ConstantTerm, ConstantTerm]) : ITerm =
    apply(t.asInstanceOf[IExpression],
          subst transform ((_, c) => i(c))).asInstanceOf[ITerm]
  def rename(t : IFormula, subst : Map[ConstantTerm, ConstantTerm]) : IFormula =
    apply(t.asInstanceOf[IExpression],
          subst transform ((_, c) => i(c))).asInstanceOf[IFormula]

  override def preVisit(t : IExpression,
                        subst : Map[ConstantTerm, ITerm]) : PreVisitResult =
    t match {
      case IConstant(c) =>
        ShortCutResult(subst.getOrElse(c, c))
      case _ : IQuantified | _ : IEpsilon => {
        val newSubst =
          subst transform ((_, value) => VariableShiftVisitor(value, 0, 1))
        UniSubArgs(newSubst)
      }
      case _ => KeepArg
    }

  def postVisit(t : IExpression,
                subst : Map[ConstantTerm, ITerm],
                subres : Seq[IExpression]) : IExpression = t update subres
}

////////////////////////////////////////////////////////////////////////////////

/**
 * Substitute variables in an expression with arbitrary terms
 */
object VariableSubstVisitor
       extends CollectingVisitor[(List[ITerm], Int), IExpression] {
  def apply(t : IExpression, substShift : (List[ITerm], Int)) : IExpression =
    VariableSubstVisitor.visit(t, substShift)
  def apply(t : ITerm, substShift : (List[ITerm], Int)) : ITerm =
    apply(t.asInstanceOf[IExpression], substShift).asInstanceOf[ITerm]
  def apply(t : IFormula, substShift : (List[ITerm], Int)) : IFormula =
    apply(t.asInstanceOf[IExpression], substShift).asInstanceOf[IFormula]

  override def preVisit(t : IExpression,
                        substShift : (List[ITerm], Int)) : PreVisitResult =
    t match {
      case IVariable(index) => {
        val (subst, shift) = substShift
        ShortCutResult(if (index >= subst.size)
                         IVariable(index + shift)
                       else
                         subst(index))
      }
      case _ : IQuantified | _ : IEpsilon => {
        val (subst, shift) = substShift
        val newSubst = for (t <- subst) yield VariableShiftVisitor(t, 0, 1)
        UniSubArgs((IVariable(0) :: newSubst, shift))
      }
      case _ => KeepArg
    }

  def postVisit(t : IExpression,
                substShift : (List[ITerm], Int),
                subres : Seq[IExpression]) : IExpression = t update subres
}

////////////////////////////////////////////////////////////////////////////////

object SymbolCollector {
  def variables(t : IExpression) : scala.collection.Set[IVariable] = {
    val c = new SymbolCollector
    c.visit(t, 0)
    c.variables
  }
  def constants(t : IExpression) : scala.collection.Set[ConstantTerm] = {
    val c = new SymbolCollector
    c.visit(t, 0)
    c.constants
  }
}

class SymbolCollector extends CollectingVisitor[Int, Unit] {
  val variables = new scala.collection.mutable.HashSet[IVariable]
  val constants = new scala.collection.mutable.HashSet[ConstantTerm]

  override def preVisit(t : IExpression, boundVars : Int) : PreVisitResult =
    t match {
      case _ : IQuantified | _ : IEpsilon => UniSubArgs(boundVars + 1)
      case _ => super.preVisit(t, boundVars)
    }

  def postVisit(t : IExpression, boundVars : Int, subres : Seq[Unit]) : Unit =
    t match {
      case IVariable(i) if (i >= boundVars) =>
        variables += IVariable(i - boundVars)
      case IConstant(c) =>
        constants += c
      case _ => // nothing
    }
}

////////////////////////////////////////////////////////////////////////////////

object Context {
  abstract sealed class Binder {
    def toQuantifier : Quantifier = throw new UnsupportedOperationException
  }
  case object ALL extends Binder {
    override def toQuantifier = Quantifier.ALL
  }
  case object EX extends Binder {
    override def toQuantifier = Quantifier.EX
  }
  case object EPS extends Binder
  
  def toBinder(q : Quantifier) = q match {
    case Quantifier.ALL => ALL
    case Quantifier.EX => EX
  }
  
  def apply[A](a : A) : Context[A] = Context(List(), +1, a)
}

case class Context[A](binders : List[Context.Binder], polarity : Int, a : A) {
  import Context._
  
  def togglePolarity = Context(binders, -polarity, a)
  def noPolarity = Context(binders, 0, a)
  def push(q : Quantifier) = Context(toBinder(q) :: binders, polarity, a)
  def push(b : Binder) = Context(b :: binders, polarity, a)
  def apply(newA : A) = Context(binders, polarity, newA)
}

abstract class ContextAwareVisitor[A, R] extends CollectingVisitor[Context[A], R] {

  override def preVisit(t : IExpression, arg : Context[A]) : PreVisitResult =
    t match {
      case INot(_) => UniSubArgs(arg.togglePolarity)
      case IBinFormula(IBinJunctor.Eqv, _, _) => UniSubArgs(arg.noPolarity)
      case IQuantified(quan, _) => {
        val actualQuan = if (arg.polarity < 0) quan.dual else quan
        UniSubArgs(arg push actualQuan)
      }
      case IEpsilon(_) => UniSubArgs(arg push Context.EPS)
      case _ => UniSubArgs(arg) // a subclass might have overridden this method
                                // and substituted a different context
    }

}

////////////////////////////////////////////////////////////////////////////////

/**
 * Push negations down to the atoms in a formula
 */
object Transform2NNF extends CollectingVisitor[Boolean, IExpression] {
  import IExpression._
  import IBinJunctor._
  
  def apply(f : IFormula) : IFormula =
    this.visit(f, false).asInstanceOf[IFormula]
    
  override def preVisit(t : IExpression, negate : Boolean) : PreVisitResult =
    t match {
      case INot(f) => TryAgain(f, !negate)  // eliminate negations
      case t@IBoolLit(b) => ShortCutResult(if (negate) !b else t)
      case LeafFormula(s) => UniSubArgs(false)
      case IBinFormula(Eqv, _, _) => SubArgs(List(negate, false))
      case ITrigger(ts, _) => SubArgs(List.fill(ts.size){false} ::: List(negate))
      case _ : IFormulaITE => SubArgs(List(false, negate, negate))
      case _ : IFormula => KeepArg
      case _ : ITerm => KeepArg
    }

  def postVisit(t : IExpression, negate : Boolean,
                subres : Seq[IExpression]) : IExpression =
    if (negate) t match {
      case IBinFormula(Eqv, _, _) | _ : ITrigger | _ : INamedPart =>
        t update subres
      case IBinFormula(And, _, _) =>
        subres(0).asInstanceOf[IFormula] | subres(1).asInstanceOf[IFormula]
      case IBinFormula(Or, _, _) =>
        subres(0).asInstanceOf[IFormula] & subres(1).asInstanceOf[IFormula]
      case IQuantified(quan, _) =>
        IQuantified(quan.dual, subres(0).asInstanceOf[IFormula])
      case LeafFormula(t) =>
        !(t.asInstanceOf[IFormula] update subres)
    } else {
      t update subres
    }
}

////////////////////////////////////////////////////////////////////////////////

/**
 * Turn a formula <code> f1 &lowast; f2 &lowast; ... &lowast; fn </code>
 * (where <code>&lowast;</code> is some binary operator) into
 * <code>List(f1, f2, ..., fn)</code>
 */
object LineariseVisitor {
  def apply(t : IFormula, op : IBinJunctor.Value) : Seq[IFormula] = {
    val parts = scala.collection.mutable.ArrayBuilder.make[IFormula]
  
    val visitor = new CollectingVisitor[Unit, Unit] {
      override def preVisit(t : IExpression, arg : Unit) : PreVisitResult = t match {
        case IBinFormula(`op`, _, _) =>
          KeepArg
        case t : IFormula => {
          parts += t
          ShortCutResult({})
        }
      }

      def postVisit(t : IExpression, arg : Unit, subres : Seq[Unit]) : Unit = {}
    }
    
    visitor.visit(t, {})
    parts.result
  }
}
