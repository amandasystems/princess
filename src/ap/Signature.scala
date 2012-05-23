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

package ap

import ap.parser.{ITerm, IFormula, IExpression, IFunction}
import ap.terfor.{ConstantTerm, TermOrder}
import ap.terfor.preds.Predicate

object Signature {
  
  abstract class FunctionType {
    def argumentTypeGuard(args : Seq[ITerm]) : IFormula
    def resultTypeGuard  (res : ITerm)       : IFormula
  }
  
  object TopFunctionType extends FunctionType {
    import IExpression._
    def argumentTypeGuard(args : Seq[ITerm]) : IFormula = i(true)
    def resultTypeGuard  (res : ITerm)       : IFormula = i(true)
  }
  
}

/**
 * Helper class for storing the sets of declared constants (of various kinds)
 * and functions, together with the chosen <code>TermOrder</code>.
 */
class Signature(val universalConstants : Set[ConstantTerm],
                val existentialConstants : Set[ConstantTerm],
                val nullaryFunctions : Set[ConstantTerm],
                val order : TermOrder,
                val domainPredicates : Set[Predicate],
                val functionTypes : Map[IFunction, Signature.FunctionType]) {
  def updateOrder(newOrder : TermOrder) =
    new Signature(universalConstants, existentialConstants,
                  nullaryFunctions, newOrder, domainPredicates, functionTypes)
}
