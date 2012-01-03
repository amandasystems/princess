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

package ap.terfor;

import ap.util.Debug

object VariableTerm {
  val AC = Debug.AC_VARIABLES
  
  val _0 = VariableTerm(0)
}

case class VariableTerm(index : Int) extends Term {

  //-BEGIN-ASSERTION-///////////////////////////////////////////////////////
  Debug.assertCtor(VariableTerm.AC, index >= 0)
  //-END-ASSERTION-/////////////////////////////////////////////////////////

  lazy val variables : Set[VariableTerm] = Set(this)

  val constants : Set[ConstantTerm] = Set.empty

  override def toString = "_" + index

}
