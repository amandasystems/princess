/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2011 Philipp Ruemmer <ph_r@gmx.net>
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

package ap.interpolants

import ap.basetypes.IdealInt
import ap.proof.certificates._
import ap.terfor.TerForConvenience._
import ap.util.Debug

/**
 * Module for simplifying a proof (certificate) by eliminating as many
 * rounding steps as possible; this is currently done in a rather naive way
 */
object ProofSimplifier {

  private val AC = Debug.AC_INTERPOLATION

  def apply(cert : Certificate) : Certificate = weaken(encode(cert)) _1

  //////////////////////////////////////////////////////////////////////////////

  /**
   * Encoding of some non-elementary proof rules into simpler ones. This will
   * eliminate all applications of Omega, DirectStrengthen, and AntiSymm
   */
  
  private def encode(cert : Certificate) : Certificate = cert match {
    
    case cert@BranchInferenceCertificate(infs, child, o) => {
      val (newInfs, newChild) = encodeInfs(infs.toList, child)
      if (newInfs == infs)
        cert update List(newChild)
      else
        BranchInferenceCertificate.checkEmptiness(newInfs, newChild, o)
    }
    
    case cert@OmegaCertificate(elimConst, boundsA, boundsB, children, order) => {
      // translate to the ordinary strengthen rule
      
      implicit val o = order

      val newChildren = for (c <- children) yield encode(c)

      val ineqResolutions =
        (for ((geq, cases) <- boundsA.iterator zip cert.strengthenCases.iterator;
              val geqCoeff = (geq.lhs get elimConst).abs;
              val strengthenedGeq = CertInequality(geq.lhs - cases);
              leq <- boundsB.iterator) yield {
           val leqCoeff = (leq.lhs get elimConst).abs
           CombineInequalitiesInference(leqCoeff, strengthenedGeq,
                                        geqCoeff, leq,
                                        CertInequality(leqCoeff * strengthenedGeq.lhs +
                                                       geqCoeff * leq.lhs),
                                        order)
         }).toList

      val darkShadow =
        BranchInferenceCertificate.checkEmptiness(ineqResolutions,
                                                  newChildren.last, order)
         
      def setupStrengthenCerts(i : Int, childrenStart : Int) : Certificate =
        if (i == boundsA.size) {
          darkShadow
        } else {
          val cases = cert.strengthenCases(i)
          val intCases = cases.intValueSafe
          val lastChild = setupStrengthenCerts(i+1, childrenStart + intCases)
          
          if (cases.isZero) {
            lastChild
          } else {
            val children =
              newChildren.slice(childrenStart,
                                childrenStart + intCases) ++ List(lastChild)
            StrengthenCertificate(boundsA(i), cases, children, order)
          }
        }
              
      setupStrengthenCerts(0, 0)
    }
    
    case cert =>
      cert update (for (c <- cert.subCertificates) yield encode(c))
    
  }

  private def encodeInfs(infs : List[BranchInference], child : Certificate)
                        : (List[BranchInference], Certificate) = infs match {

    case List() =>
      (List(), encode(child))
    
    case inf :: otherInfs => {
      val (newOtherInfs, newChild) = encodeInfs(otherInfs, child)
    
      inf match {

        case DirectStrengthenInference(inEq, eq, result, order) => {
          // we simply translate DirectStrengthen to the ordinary Strengthen rule

          implicit val o = order

          val closeCert = CloseCertificate(Set(CertNegEquation(0)), o)
        
          val redInf =
            if (inEq.lhs == eq.lhs) {
              ReduceInference(List((-1, !eq)), eq, CertNegEquation(0), o)
            } else {
              //-BEGIN-ASSERTION-/////////////////////////////////////////////////////
              Debug.assertInt(AC, inEq.lhs == -eq.lhs)
              //-END-ASSERTION-///////////////////////////////////////////////////////
              ReduceInference(List((1, CertEquation(inEq.lhs))), eq,
                              CertNegEquation(0), o)
            }
        
          val eqCaseCert =
            BranchInferenceCertificate(List(redInf), closeCert, o)
        
          val inEqCaseCert =
            BranchInferenceCertificate.checkEmptiness(newOtherInfs, newChild, o)
        
          val strengthenCert =
            StrengthenCertificate(inEq, 1, List(eqCaseCert, inEqCaseCert), o)
        
          (List(), strengthenCert)
        }

        case AntiSymmetryInference(left, right, result, order) => {
          // we simply translate AntiSymmetry to the Strengthen rule
        
          implicit val o = order
          
          val closeCert = CloseCertificate(Set(CertInequality(-1)), o)
         
          val combineInEqInf =
            CombineInequalitiesInference(1, CertInequality(left.lhs - 1), 1, right,
                                         CertInequality(-1), o)
        
          val eqCaseCert =
            BranchInferenceCertificate.checkEmptiness(newOtherInfs, newChild, o)
        
          val inEqCaseCert =
            BranchInferenceCertificate(List(combineInEqInf), closeCert, o)
        
          val strengthenCert =
            StrengthenCertificate(left, 1, List(eqCaseCert, inEqCaseCert), o)
        
          (List(), strengthenCert)
        }

        case inf => (inf :: newOtherInfs, newChild)
      }
    }
  }
  
  //////////////////////////////////////////////////////////////////////////////
  
  /**
   * Checking whether it is ok to weaken individual inequalities, leaving
   * out applications of Simp, Strengthen, etc. 
   */
  
  type WeakeningRange = Map[CertInequality, IdealInt]
  
  private def mergeWeakening(a : WeakeningRange, b : WeakeningRange)
                          : WeakeningRange =
    (a /: b) { case (newBounds, (ineq, bound)) =>
      newBounds + (ineq -> (bound min newBounds.getOrElse(ineq, bound)))
    }
 
  private def ineqSubset(s : Set[CertFormula]) =
    s collect { case f : CertInequality => f }

  //////////////////////////////////////////////////////////////////////////////

  /**
   * Recursive weakening of a certificate
   */
  private def weaken(cert : Certificate) : (Certificate, WeakeningRange) = cert match {
    
    case CloseCertificate(contradFors, _) => {
      //-BEGIN-ASSERTION-///////////////////////////////////////////////////////       
      Debug.assertInt(AC, contradFors.size == 1 && contradFors.head.isFalse)
      //-END-ASSERTION-/////////////////////////////////////////////////////////
      
      contradFors.head match {
        case f@CertInequality(lc) => {
          //-BEGIN-ASSERTION-///////////////////////////////////////////////////       
          Debug.assertInt(AC, lc.isConstant && lc.constant.signum < 0)
          //-END-ASSERTION-/////////////////////////////////////////////////////
          (cert, Map(f -> (-lc.constant - 1)))
        }
        case _ =>
          (cert, Map())
      }
    }
    
    case cert@BranchInferenceCertificate(infs, child, o) => {
      val (newInfs, newChild, wr) = weakenInfs(infs.toList, child)
      if (newInfs == infs)
        (cert update List(newChild), wr)
      else
        (BranchInferenceCertificate.checkEmptiness(newInfs, newChild, o), wr)
    }
    
    case cert => {
      val (newChildren, weakenings) =
        (for (c <- cert.subCertificates) yield weaken(c)).unzip
      (cert update newChildren, weakenings reduceLeft mergeWeakening)
    }
  }
  
  //////////////////////////////////////////////////////////////////////////////
  
  /**
   * Recursive weakening of branch inferences
   */
  private def weakenInfs(infs : List[BranchInference], child : Certificate)
        : (List[BranchInference], Certificate, WeakeningRange) = infs match {

    case List() => {
      val (newChild, weakening) = weaken(child)
      (List(), newChild, weakening)
    }
    
    case inf :: otherInfs => {
      val (newOtherInfs, newChild, wkn) = weakenInfs(otherInfs, child)
      
      def defaultWeakening =
        wkn -- ineqSubset(inf.providedFormulas) ++ (
          for (f <- ineqSubset(inf.assumedFormulas)) yield (f -> IdealInt.ZERO))
      
      inf match {
        case inf@ReduceInference(_, target : CertInequality,
                                 result : CertInequality, _) => {
          val wkn2 =
            (wkn get target, wkn get result) match {
              case (None, Some(resultW)) => wkn - result + (target -> resultW)
              case (_, None)             => wkn
              case _                     => defaultWeakening
            }
          
          (inf :: newOtherInfs, newChild, wkn2)
        }
        
        case inf@CombineInequalitiesInference(leftCoeff, leftInEq,
                                              rightCoeff, rightInEq, result, _) => {
          val wkn2 =
            (wkn get leftInEq, wkn get rightInEq, wkn get result) match {
              case (None, None, Some(resultW)) =>
                wkn - result + (leftInEq -> (resultW / leftCoeff),
                                rightInEq -> (resultW / rightCoeff))
              case (_, _, None)                => wkn
              case _                           => defaultWeakening
            }
            
          (inf :: newOtherInfs, newChild, wkn2)
        }
                  
        case inf@SimpInference(target : CertInequality,
                               result : CertInequality, _) =>
          if (inf.constantDiff.isZero) {
            // nothing to be simplified, but we can propagate the weakening
            // bound
            val wkn2 =
              (wkn get target, wkn get result) match {
                case (None, Some(resultW)) => wkn - result + (target -> resultW)
                case (_, None)             => wkn
                case _                     => defaultWeakening
              }
            (inf :: newOtherInfs, newChild, wkn2)
          } else (wkn get result) match {
            case Some(resultW) if (inf.constantDiff <= resultW * inf.factor) => {
              // The simplification can be eliminated: everywhere in this
              // subproof, replace <code>result</code> with
              // <code>target = result * factor + constantDiff</code>
              val (newOtherInfs2, newChild2) =
                elimSimpInfs(newOtherInfs, newChild,
                             Map(result -> (target, inf.factor, inf.constantDiff)))
         
              weakenInfs(newOtherInfs2, newChild2)
            }
            case _ => {
              // it might be possible to eliminate simplifications before this
              // one
              val newTargetW =
                wkn.getOrElse(target, inf.factor) min (inf.factor - inf.constantDiff - 1)
              val wkn2 =
                wkn - result + (target -> newTargetW)
              (inf :: newOtherInfs, newChild, wkn2)
            }
          }
                                              
        case inf => (inf :: newOtherInfs, newChild, defaultWeakening)
      }
    }
  }
  
  //////////////////////////////////////////////////////////////////////////////

  /**
   * Recursive replacement of an inequality <code>t >= 0</code> with the
   * weakened inequality <code>a * t + b >= 0</code>
   */
  
  type ReplMap = Map[CertInequality, (CertInequality, IdealInt, IdealInt)]
  
  private def elimSimp(cert : Certificate,
                       replacement : ReplMap) : Certificate = cert match {
    case cert@CloseCertificate(contradFors, _) => {
      val newContrad =
        for (f <- contradFors) yield f match {
          case f : CertInequality => replacement.getOrElse(f, (f, null, null)) _1
          case f => f
        }
      
      if (newContrad == contradFors)
        cert
      else
        cert.copy(localAssumedFormulas = newContrad)
    }

    case cert@BranchInferenceCertificate(infs, child, o) => {
      val (newInfs, newChild) = elimSimpInfs(infs.toList, child, replacement)
      BranchInferenceCertificate.checkEmptiness(newInfs, newChild, o)
    }

    case cert => {
      val newChildren =
        for ((c, i) <- cert.subCertificates.zipWithIndex)
          yield elimSimp(c, replacement -- ineqSubset(cert.localProvidedFormulas(i)))
      cert update newChildren
    }
  }

  //////////////////////////////////////////////////////////////////////////////

  private def elimSimpInfs(infs : List[BranchInference], child : Certificate,
                           replacement : ReplMap)
                          : (List[BranchInference], Certificate) = infs match {

    case List() =>
      (List(), elimSimp(child, replacement))
    
    case inf :: otherInfs => {
      val (newInf, newRepl) = inf match {
        
        case inf@ReduceInference(equations,
                                 target : CertInequality,
                                 result : CertInequality, _)
          if (replacement contains target) => {
               
          val (newTarget, factor, constantDiff) = replacement(target)
          val newEquations = for ((c, eq) <- equations) yield (c * factor, eq)
          val newResult = CertInequality(result.lhs * factor + constantDiff)
          (List(inf.copy(equations = newEquations,
                         targetLit = newTarget, result = newResult)),
           replacement + (result -> (newResult, factor, constantDiff)))
        }
                  
        case inf@CombineInequalitiesInference(leftCoeff, leftInEq,
                                              rightCoeff, rightInEq, result, _)
          if ((replacement contains leftInEq) || (replacement contains rightInEq)) => {
              
          val (newLeft, leftFactor, leftDiff) =
            replacement.getOrElse(leftInEq, (leftInEq, IdealInt.ONE, IdealInt.ZERO))
          val (newRight, rightFactor, rightDiff) =
            replacement.getOrElse(rightInEq, (rightInEq, IdealInt.ONE, IdealInt.ZERO))

          val commonFactor = (leftFactor / (leftCoeff gcd leftFactor)) lcm
                             (rightFactor / (rightCoeff gcd rightFactor))
          val newLeftCoeff = commonFactor * leftCoeff / leftFactor
          val newRightCoeff = commonFactor * rightCoeff / rightFactor
          val commonDiff = newLeftCoeff * leftDiff + newRightCoeff * rightDiff
            
          val newResult = CertInequality(result.lhs * commonFactor + commonDiff)
            
          (List(inf.copy(leftCoeff = newLeftCoeff, leftInEq = newLeft,
                         rightCoeff = newRightCoeff, rightInEq = newRight,
                         result = newResult)),
           replacement + (result -> (newResult, commonFactor, commonDiff)))
        }

        case inf@SimpInference(target : CertInequality,
                               result : CertInequality, _)
          if (replacement contains target) => {

          val (newTarget, factor, constantDiff) = replacement(target)

          if (inf.constantDiff.isZero) {
            // just skip this simplification step
            (List(),
             replacement + (result -> (newTarget, factor * inf.factor, constantDiff)))
          } else {
            // the replacement should not change the result of the simplification
            (List(inf.copy(targetLit = newTarget)), replacement)
          }
        }
          
        case inf => (List(inf), replacement -- ineqSubset(inf.providedFormulas))
      }

      val (newOtherInfs, newChild) = elimSimpInfs(otherInfs, child, newRepl)
      (newInf ::: newOtherInfs, newChild)
    }
  }
}
