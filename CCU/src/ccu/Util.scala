package ccu;

import scala.collection.mutable.{Set => MSet}

class Util[TERM, FUNC] {
  // Calculating log_2 (b)
  // Used for cacluating number of bits needed
  def binlog(b : Int) : Int = {
    Timer.measure("binlog") {
      var bits = b
      var log = 0
      if ((bits & 0xffff0000) != 0) {
        bits >>>= 16
        log = 16
      }
      
      if (bits >= 256) {
        bits >>>= 8
        log += 8
      }
      if (bits >= 16) {
        bits >>>= 4
        log += 4
      }
      
      if (bits >= 4) {
        bits >>>= 2
        log += 2
      }

      // TODO: Add 1 for luck!
      // Actually only needed for 2, 4, 8, 16, 32 ...
      return log + ( bits >>> 1 ) + 1
    }
  }

  def CC[FUNC, TERM](
    sets : MSet[Set[TERM]], 
    functions : List[(FUNC, List[TERM], TERM)],
    assignments : List[(TERM, TERM)] = List()) 
      : Set[Set[TERM]] = {
    def rep(t : TERM) : TERM = {
      for (s <- sets)
        if (s contains t)
          return s.minBy(_.toString)
      throw new Exception("No set contains t?")
    }

    def set(t : TERM) : Set[TERM] = {
      for (s <- sets)
        if (s contains t)
          return s
      throw new Exception("No set contains t?")
    }

    def mergeSets(t1 : TERM, t2 : TERM) : Unit = {
      val set1 = set(t1)
      val set2 = set(t2)

      val newset = set1 ++ set2
      sets -= set1
      sets -= set2
      sets += newset
    }

    // First merge assigned terms
    for ((s, t) <- assignments) {
      mergeSets(s, t)
    }

    // Fix-point calculation
    var changed = true
    while (changed) {
      changed = false
      // All pairs of functions, if args_i = args_j, merge s_i with s_j
      for ((f_i, args_i, s_i) <- functions;
        (f_j, args_j, s_j) <- functions;
        if (f_i == f_j && set(s_i) != set(s_j))) {
        var argsEquals = true
        for (i <- 0 until args_i.length) {

          if (set(args_i(i)) != set(args_j(i)))
            argsEquals = false
        }
        if (argsEquals) {
          mergeSets(s_i, s_j)
          changed = true
        }
      }
    }

    sets.toSet
  }


  //
  //  Disequality check
  //

  // List all disequalities, and see which ones can never be united
  // Only send in pairs that are possible, so domains doesn't have to be passed
  // eq shows POSSIBLE equalities
  def disequalityCheck(eq : Array[Array[Int]],
    functions : List[(FUNC, List[Int], Int)]) = {

    var changed = true
    while (changed) {
      changed = false

      // Functionality & Transitivity
      for ((f_i, args_i, s_i) <- functions;
        (f_j, args_j, s_j) <- functions;
        if (f_i == f_j && s_i != s_j))
      {
        var equal = true
        for (i <- 0 until args_i.length) {
          if (eq(args_i(i) min args_j(i))(args_i(i) max args_j(i)) == 0) {
            equal = false
          }
        }

        // Functionality
        if (equal) {
          if (eq(s_i min s_j)(s_i max s_j) == 0) {
            // println(">" + s_i + " = " + s_j + ", because of " +
            // f_i + "(" + args_i + ") = " + f_j + "(" + args_j + ")")
            eq(s_i)(s_j) = 1
            eq(s_j)(s_i) = 1
            changed = true
          }

          // "Transitivity"
          for (i <- 0 until eq.length) {
            for (j <- 0 until eq.length) {
              if (eq(s_i)(i) != 0 && eq(s_j)(j) != 0 && eq(i)(j) == 0) {
                // println(">" + i + " = " + j + ", because of " + s_i +
                // " = " + i + " and " + s_j + " = " + j)
                eq(i)(j) = 1
                eq(j)(i) = 1
                changed = true
              }
            }
          }
        }
      }

    }
    eq
  }


}
