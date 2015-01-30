/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2009-2015 Philipp Ruemmer <ph_r@gmx.net>
 *
 * Princess is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princess is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princess.  If not, see <http://www.gnu.org/licenses/>.
 */

package ap.proof.tree;

import ap.basetypes.IdealInt
import ap.proof._
import ap.proof.goal._
import ap.parameters.Param
import ap.terfor.{TermOrder, ConstantTerm}
import ap.terfor.conjunctions.{Conjunction, Quantifier}
import ap.terfor.preds.Predicate
import ap.terfor.linearcombination.LinearCombination
import ap.util.{Debug, Seqs}

import ccu.CCUSolver
import ccu.LazySolver
import ccu.TableSolver

import scala.collection.mutable.{HashMap => MHashMap, HashSet => MHashSet,
                                 Map => MMap, LinkedHashMap}

object ProofTree {
  
  private val AC = Debug.AC_PROOF_TREE
  private val CCUSolver = new LazySolver[ConstantTerm, Predicate]
  
}

trait ProofTree {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////////
//  Debug.assertCtor(ProofTree.AC,
//                   (order isSortingOf closingConstraint) &&
//                   (constantFreedom freeConstsAreUniversal bindingContext) &&
//                   (closingConstantFreedom freeConstsAreUniversal bindingContext) &&
//                   closingConstantFreedom <= constantFreedom)
  //-END-ASSERTION-/////////////////////////////////////////////////////////////

  val subtrees : Seq[ProofTree]
  
  /**
   * The fully simplified closing constraint
   */
  val closingConstraint : Conjunction

  /**
   * The constants that can be considered free (resp., that have to be
   * considered non-free) in this proof tree.
   */
  val closingConstantFreedom : ConstantFreedom
  
  /**
   * <code>true</code> if it is possible to apply rules to any goal in this
   * tree
   */
  val stepPossible : Boolean
  
  /**
   * <code>true</code> if there are chances that the
   * <code>closingConstraint</code> of this tree changes by applying rules
   * to any goal
   */
  val stepMeaningful : Boolean
  
  /**
   * <code>true</code> if the sets of free constants have reached a fixed point
   */
  val fixedConstantFreedom : Boolean
  
  /**
   * the vocabulary available at a certain node in the proof
   */
  val vocabulary : Vocabulary
  
  def order : TermOrder = vocabulary.order
  def bindingContext : BindingContext = vocabulary.bindingContext
  def constantFreedom : ConstantFreedom = vocabulary.constantFreedom
  
  /**
   * <code>true</code> if this proof tree can be closed using some
   * CCU unifier
   */
  lazy val ccUnifiable : Boolean = unifiabilityStatus._1

  /**
   * <code>true</code> if this proof tree can be closed using some
   * CCU unifier, only assigning variables that were locally introduced
   * in this tree
   */
  lazy val ccUnifiableLocally : Boolean = unifiabilityStatus._2

  // HACK: remember whether we have already checked cc-unifiability here
  private var unifiabilityChecked = false

  protected def unifiabilityString : String =
    if (unifiabilityChecked && ccUnifiableLocally)
      "(unconditionally closable)"
    else if (unifiabilityChecked && ccUnifiable)
      "(closable)"
    else
      "(unknown)"

  //////////////////////////////////////////////////////////////////////////////

  private def domainsFromContext(
                      constantSeq : List[(Quantifier, Set[ConstantTerm])])
                    : (Map[ConstantTerm, Set[ConstantTerm]],
                       Map[ConstantTerm, Set[ConstantTerm]],
                       Set[ConstantTerm],
                       Set[ConstantTerm]) = constantSeq match {
    case List() => (Map(), Map(), Set(), Set())

    case (Quantifier.ALL, consts) :: rest => {
      val (restMap1, restMap2, restSet, restUniSet) =
        domainsFromContext(rest)
      (restMap1, restMap2, restSet ++ consts, restUniSet ++ consts)
    }

    case (Quantifier.EX, preConsts) :: rest => {
      val (restMap1, restMap2, restSet, preRestUniSet) =
        domainsFromContext(rest)
      val consts = preConsts.toSeq.sortBy(_.name)

      // make sure that there is some greatest universal constant
      val restUniSet =
        if (preRestUniSet.isEmpty) Set(consts.head) else preRestUniSet

      val newRestMap1 = restMap1 ++ {
        for (c <- consts.iterator) yield (c -> (restUniSet + c))
      }

      val newRestMap2 =
        restMap2 ++ (for (c <- consts.iterator) yield (c -> Set(c)))
      
      (newRestMap1, newRestMap2, restSet ++ consts, restUniSet)
    }
  }

  //////////////////////////////////////////////////////////////////////////////

  private type CCUProblem =
    (Map[ConstantTerm, Set[ConstantTerm]],                 // domains
     List[List[(ConstantTerm, ConstantTerm)]],             // goals
     List[(Predicate, List[ConstantTerm], ConstantTerm)])  // function apps

  private def toConstant(lc : LinearCombination,
                         intLiteralConsts : MMap[IdealInt, ConstantTerm])
                        : ConstantTerm = {
    //-BEGIN-ASSERTION-/////////////////////////////////////////////////////////
    Debug.assertPre(ProofTree.AC,
                    (lc.isConstant &&
                     (lc.constant.isZero || lc.constant.isOne)) ||
                    (lc.size == 1 &&
                     lc.leadingCoeff.isOne &&
                     lc.leadingTerm.isInstanceOf[ConstantTerm]))
    //-END-ASSERTION-///////////////////////////////////////////////////////////

    if (lc.isConstant)
      intLiteralConsts.getOrElseUpdate(lc.constant,
                                       new ConstantTerm ("int_" + lc.constant))
    else
      lc.leadingTerm.asInstanceOf[ConstantTerm]
  }

  private def constructUnificationProblems(
                    tree : ProofTree,
                    domains : Map[ConstantTerm, Set[ConstantTerm]],
                    uniConsts : Set[ConstantTerm],
                    intLiteralConsts : MMap[IdealInt, ConstantTerm])
                   : List[CCUProblem] =
    if (tree.unifiabilityChecked && tree.ccUnifiableLocally) {
      // then this subtree can be ignored, we have already
      // shown that it can be closed
      List()
    } else if (tree.unifiabilityChecked && !tree.ccUnifiable) {
      // then there is a subtree that cannot be closed, and
      // also the unification problem as a whole does not have
      // any solutions.
      // return a problem with empty goal, which cannot be closed
      List((Map(), List(), List()))
    } else tree match {

      case QuantifiedTree(Quantifier.ALL, consts, subtree) =>
        constructUnificationProblems(subtree, domains,
                                     uniConsts ++ consts,
                                     intLiteralConsts)
  
      case QuantifiedTree(Quantifier.EX, consts, subtree) => {
        val newDomains = domains ++ {
          for (c <- consts.iterator) yield (c -> (uniConsts + c))
        }
  
        constructUnificationProblems(subtree, newDomains, uniConsts,
                                     intLiteralConsts)
      }
  
      case StrengthenTree(conj, subtree) =>
        throw new Exception
  
      case WeakenTree(disj, subtree) =>
        throw new Exception
//        constructUnificationProblems(subtree, domains, disj :: furtherDisjuncts)
  
      case AndTree(leftTree, rightTree, _) => {
        val problems1 =
          constructUnificationProblems(leftTree, domains, uniConsts, intLiteralConsts)
        val problems2 =
          constructUnificationProblems(rightTree, domains, uniConsts, intLiteralConsts)
        problems1 ++ problems2
      }
  
      case goal : Goal if (goal.facts.isFalse) =>
        List()

      case goal : Goal => {
        val funPreds = Param.FUNCTIONAL_PREDICATES(goal.settings)
        val predConj = goal.facts.predConj

        val funApps =
          (for (a <- predConj.positiveLits.iterator;
                if (funPreds contains a.pred)) yield {
             val consts =
               (for (lc <- a.iterator)
                yield toConstant(lc, intLiteralConsts)).toList
             (a.pred, consts.init, consts.last)
           }).toList
  
        // check whether there are further positive equations that we have to
        // convert to function applications
        val eqFunApps =
          (for (lc <- goal.facts.arithConj.positiveEqs.iterator;
                app <- {
                  //-BEGIN-ASSERTION-///////////////////////////////////////////
                  Debug.assertInt(ProofTree.AC,
                         lc match {
                           case Seq((IdealInt.ONE, _ : ConstantTerm),
                                    (IdealInt.MINUS_ONE, _ : ConstantTerm)) => true
                           case _ => false
                         })
                  //-END-ASSERTION-/////////////////////////////////////////////
  
                  val tempPred = new Predicate ("tempPred", 0)
  
                  Seqs.doubleIterator((tempPred, List(),
                                       lc.getTerm(0).asInstanceOf[ConstantTerm]),
                                      (tempPred, List(),
                                       lc.getTerm(1).asInstanceOf[ConstantTerm]))
                })
           yield app).toList
  
        val allFunApps = funApps ++ eqFunApps
  
        ////////////////////////////////////////////////////////////////////////
  
        val predUnificationGoals =
          (for (a <- predConj.positiveLits.iterator;
                b <- predConj.negativeLitsWithPred(a.pred).iterator) yield {
             (for ((lcA, lcB) <- a.iterator zip b.iterator)
              yield (toConstant(lcA, intLiteralConsts),
                     toConstant(lcB, intLiteralConsts))).toList
           }).toList
  
        val eqUnificationGoals =
          (for (lc <- goal.facts.arithConj.negativeEqs.iterator) yield {
             lc.size match {
               case 1 =>
                 List((toConstant(lc, intLiteralConsts),
                       toConstant(LinearCombination.ZERO, intLiteralConsts)))
               case 2 if (lc.constants.size == 1 && lc.leadingCoeff.isOne) =>
                 List((lc.leadingTerm.asInstanceOf[ConstantTerm],
                       toConstant(LinearCombination(-lc.constant), intLiteralConsts)))
               case 2 => {
                 //-BEGIN-ASSERTION-////////////////////////////////////////////
                 Debug.assertInt(ProofTree.AC,
                         lc.size == 2 &&
                         lc.getCoeff(0).isOne && lc.getCoeff(1).isMinusOne &&
                         lc.getTerm(0).isInstanceOf[ConstantTerm] &&
                         lc.getTerm(1).isInstanceOf[ConstantTerm])
                 //-END-ASSERTION-//////////////////////////////////////////////
  
                 List((lc.getTerm(0).asInstanceOf[ConstantTerm],
                       lc.getTerm(1).asInstanceOf[ConstantTerm]))
               }
             }
           }).toList
  
        val unificationGoals = predUnificationGoals ::: eqUnificationGoals
  
        List((domains, unificationGoals, allFunApps))
      }
    }

  //////////////////////////////////////////////////////////////////////////////

  private def checkUnifiability(
                 globalDomains : Map[ConstantTerm, Set[ConstantTerm]],
                 globalConsts : Iterable[ConstantTerm],
                 intConsts : Iterable[ConstantTerm],
                 unificationProblems : List[CCUProblem])
               : Boolean = {

    val (domains, goals, funApps) = unificationProblems.unzip3

    val allDomains = new MHashMap[ConstantTerm, Set[ConstantTerm]]
    val allConsts = new MHashSet[ConstantTerm]

    allDomains ++= globalDomains
    allConsts ++= globalConsts
    allConsts ++= intConsts
  
    for (domain <- domains.iterator; (c, consts) <- domain.iterator) {
      // domains have to be consistent
      //-BEGIN-ASSERTION-///////////////////////////////////////////////////////
      Debug.assertInt(ProofTree.AC, (allDomains get c) match {
                        case Some(oldConsts) => consts == oldConsts
                        case None => true
                      })
      //-END-ASSERTION-/////////////////////////////////////////////////////////

      allDomains.put(c, consts)
      allConsts += c
      allConsts ++= consts
    }

    for (l <- goals; l2 <- l; (c1, c2) <- l2) {
      allConsts += c1
      allConsts += c2
    }

    for (a <- funApps; (_, cs, c) <- a) {
      allConsts ++= cs
      allConsts += c
    }

    ap.util.Timer.measure("CCUSolver") {  
    // val solver = new CCUSolver[ConstantTerm, Predicate]
// Console.withOut(ap.CmdlMain.NullStream) {
//     (ProofTree.CCUSolver.solve(allConsts.toList.sortBy(_.name),
//         allDomains.toMap,
//         goals, funApps)).isDefined }
      ProofTree.CCUSolver.createProblem(allDomains.toMap, goals, funApps)

      ProofTree.CCUSolver.solve() == ccu.Result.SAT
    }
  }

  //////////////////////////////////////////////////////////////////////////////

  private lazy val unifiabilityStatus = ap.util.Timer.measure("unification") {
//    println
    print("Trying to close subtree ")
//    println(this)

    val intLiteralConstMap = new LinkedHashMap[IdealInt, ConstantTerm]
    val uniConsts =
      if (this.order.orderedConstants.isEmpty)
        Set(new ConstantTerm ("X"))
      else
        this.order.orderedConstants
    val unificationProblemsPre =
      constructUnificationProblems(this, Map(),
                                   uniConsts,
                                   intLiteralConstMap)

    val intLiteralConsts = intLiteralConstMap.values.toArray
    val intLiteralGoals =
      (for (i <- 0 until (intLiteralConsts.size-1);
            j <- (i+1) until intLiteralConsts.size)
       yield List((intLiteralConsts(i), intLiteralConsts(j)))).toList

    val unificationProblems =
      for ((a, goals, c) <- unificationProblemsPre)
      yield (a, intLiteralGoals ::: goals, c)

//    println(unificationProblems)
    print("(" + unificationProblems.size + " parallel problems) ... ")

    val res =
    if (unificationProblems.isEmpty) {
      (true, true)
    } else if (unificationProblems exists {
                 case (_, goals, _) => goals.isEmpty
               }) {
      (false, false)
    } else {
      val (fullDomains, restrictedDomains, globalConsts, _) =
        domainsFromContext(bindingContext.constantSeq)

//      println("restricted domains:")
//      println(restrictedDomains)

/*
      if (checkUnifiability(fullDomains, globalConsts,
                            unificationProblems)) {
        (true,
         checkUnifiability(restrictedDomains, globalConsts,
                           unificationProblems))
      } else {
        (false, false)
      }
 */

      if (checkUnifiability(restrictedDomains, globalConsts,
                            intLiteralConsts,
                            unificationProblems))
        (true, true)
      else {
//        println("full domains:")
//        println(fullDomains)
        (checkUnifiability(fullDomains, globalConsts,
                           intLiteralConsts,
                           unificationProblems),
         false)
      }

    }

    println(res)

    unifiabilityChecked = true

    res
  }

}
