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

package ap.terfor.linearcombination;

import scala.collection.mutable.{PriorityQueue, Buffer, ArrayBuffer}

import ap.basetypes.IdealInt
import ap.util.{PeekIterator, PriorityQueueWithIterators, Debug}

/**
 * Class for mixing together a number of <code>LinearCombination</code>s that
 * are sorted by the same <code>TermOrder</code> (namely by <code>order</code>)
 */
class LCBlender(order : TermOrder) extends PeekIterator[(IdealInt,Term)] {
  
  private val AC = Debug.AC_LINEAR_COMB
   
  private implicit def orderPairs(thisTerm : (IdealInt,Term)) =
    new Ordered[(IdealInt,Term)] {
      def compare(thatTerm : (IdealInt,Term)) : Int = {
        order.compare(thisTerm _2, thatTerm _2)
      }
    }
   
  private val terms = new PriorityQueueWithIterators[(IdealInt,Term)]
  
  def +=(coeff : IdealInt, lc : LinearCombination) : Unit =
    this ++= List((coeff, lc))
  
  def ++=(lcs : Iterable[(IdealInt, LinearCombination)]) : Unit = {
    if (nextVal != null) terms += nextVal

    for ((coeff, lc) <- lcs) {

      //////////////////////////////////////////////////////////////////////////
      Debug.assertPre(AC, lc isSortedBy order)
      //////////////////////////////////////////////////////////////////////////

      terms += ScalingIterator(coeff, lc.elements)
    }

    nextVal = computeNext
  }
  
  /**
   * Extract the next monomial from the priority queue, return <code>null</code>
   * if the queue does not contain any further non-zero monomials. If the result
   * is not <code>null</code>, it is guaranteed that the queue only contains
   * monomials that are strictly smaller than the returned monomial
   */
  private def computeNext : (IdealInt,Term) = {
    while (!terms.isEmpty) {
      val (firstC, firstT) = terms.next

      var currentCoeff = firstC
      while (!terms.isEmpty && (terms.max _2) == firstT) {
        val nextC = (terms.next _1)
        currentCoeff = currentCoeff + nextC
      }
     
      if (!currentCoeff.isZero) return (currentCoeff, firstT)
    }
    
    null
  }
  
  //////////////////////////////////////////////////////////////////////////////
  
  private val resultBuf : Buffer[(IdealInt,Term)] = new ArrayBuffer

  def result : LinearCombination =
    LinearCombination.createFromSortedSeq(resultBuf, order)
  
  private var nextVal : (IdealInt,Term) = computeNext
  
  def hasNext = (nextVal != null)
  
  def next = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertInt(AC,
                    resultBuf.isEmpty ||
                    order.compare(resultBuf.last _2, nextVal _2) > 0)
    ////////////////////////////////////////////////////////////////////////////

    resultBuf += nextVal
    val res = nextVal
    nextVal = computeNext
    res
  }
  
  def peekNext = nextVal

}
