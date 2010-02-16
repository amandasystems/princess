/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2009 Philipp Ruemmer <ph_r@gmx.net>
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

package ap.proof.certificates

import ap.terfor.TermOrder
import ap.terfor.conjunctions.Conjunction
import ap.terfor.inequalities.InEqConj
import ap.terfor.TerForConvenience._
import ap.util.Debug

object SplitEqCertificate {
  
  private val AC = Debug.AC_CERTIFICATES
  
}

/**
 * Certificate corresponding to splitting a negated equation into two
 * inequalities.
 */
case class SplitEqCertificate(leftInEq : InEqConj, rightInEq : InEqConj,
                              _leftChild : Certificate, _rightChild : Certificate,
                              _order : TermOrder) extends {
  
  val localAssumedFormulas : Set[Conjunction] = Set({
    implicit val o = _order
    leftInEq(0) + 1 =/= 0
  })
  
  val localProvidedFormulas : Seq[Set[Conjunction]] =
    Array(Set(leftInEq), Set(rightInEq))
  
} with BinaryCertificate(_leftChild, _rightChild, _order) {

  //////////////////////////////////////////////////////////////////////////////
  Debug.assertCtor(SplitEqCertificate.AC,
                   leftInEq.size == 1 && rightInEq.size == 1 &&
                   {
                     implicit val o = _order
                     leftInEq(0) + 1 == -(rightInEq(0) + 1)
                   })
  //////////////////////////////////////////////////////////////////////////////

  override def toString : String =
    "SplitEq(" + localAssumedFormulas.elements.next + ", " +
    leftChild + ", " + rightChild + ")"

}
