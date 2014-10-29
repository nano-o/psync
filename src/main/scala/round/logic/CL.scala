package round.logic

import round.formula._

import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._
import dzufferey.utils.Namer

object CL {
  
  val procType = UnInterpreted("ProcessID")
  val HO = UnInterpretedFct("HO",Some(procType ~> FSet(procType)))
  val n = Variable("n").setType(Int)

  val vtt = Variable("v_tt").setType(procType)
  val vtf = Variable("v_tf").setType(procType)
  val vft = Variable("v_ft").setType(procType)
  val vff = Variable("v_ff").setType(procType)

  def hasHO(f: Formula): Boolean = {
    def check(acc: Boolean, f: Formula) = f match {
      case Application(UnInterpretedFct("HO",_,_), _) => true
      case _ => acc
    }
    FormulaUtils.collect(false, check, f)
  }

  protected def normalize(f: Formula) = {
    //TODO some CNF conversion ?
    //TODO purification before or after instantiation ?
    val f1 = Simplify.normalize(f)
    val f2 = Simplify.nnf(f1)
    val f3 = Simplify.boundVarUnique(f2)
    f3
  }

  //unsupported quantifiers are implicitely universal, we care about the ∀ in ∀∃φ
  //TODO should still allows EPR no quantified processID below
  protected def getUnsupportedQuantifierPrefix(f: Formula): (Formula, List[Variable]) = {
    val f2 = Simplify.pnf(f)
    val (f3,vs) = Quantifiers.getUniversalPrefix(f2)
    val (supported, unsupported) = vs.toList.partition( v => v.tpe == procType)
    Logger("CL", Info, "unsupported quantifiers are: " + unsupported.map(v => v.name + ": " + v.tpe).mkString(", "))
    val f4 = ForAll(supported, f3)
    (f4, unsupported)
  }


  protected def matchQuantifiers( qf: Formula, 
                                  vs: List[Variable],
                                  existentials: Iterable[Variable],
                                  groundTerms: Iterable[Formula]
                                ): Iterable[Formula] = {

    def findMatch(v: Variable) = {
      val prefered = existentials.filter(_.tpe == v.tpe)
      if (!prefered.isEmpty) {
        prefered
      } else {
        Logger("CL", Notice, "did not find instantiation candidate among existential for " + v + ": " + v.tpe)
        groundTerms.filter(_.tpe == v.tpe)
      }
    }

    vs.foldLeft(List(qf))( (acc, v) => {
      val candidates = findMatch(v)
      if (candidates.isEmpty) {
        Logger("CL", Notice, "did not find any instantiation candidate for " + v + ": " + v.tpe)
        acc.map( f => ForAll(List(v), f) )
      } else {
        acc.flatMap( f => Quantifiers.instantiateWithTerms(v, f, candidates.toSet) )
      }
    })
  }

  /** preprocess and reduce (hypothesis ∧ ¬conclusion),
   *  returned formula can be checked for satisfiability. */
  def entailment(hypothesis: Formula, conclusion: Formula): Formula = {
    val h1 = normalize(hypothesis)
    val c1 = normalize(Not(conclusion))
    
    val (h2, ext) = Quantifiers.getExistentialPrefix(h1)
    val gt = FormulaUtils.collectGroundTerms(h2)
    //what can be used to instantiate the unsupported quantifiers: ext ∪ gt

    val cs1 = FormulaUtils.getConjuncts(c1)
    val cs2 = cs1.flatMap( c => {
      val (qf, vs) = getUnsupportedQuantifierPrefix(c)
      matchQuantifiers(qf, vs, ext, gt)
    })
    val cs3 = cs2.map(Quantifiers.fixUniquelyDefinedUniversal)

    val query = cs3.foldLeft(h2)(And(_,_))
    reduce(query)
  }

  protected def collectComprehensionDefinitions(conjuncts: List[Formula]): Set[(Set[Variable], Formula, Formula)] = {
    //look at all the terms of type FSet(procType)
    val init = Set[(Set[Variable], Formula, Formula)]()
    def process(acc: Set[(Set[Variable], Formula, Formula)], bound: Set[Variable], f: Formula) = f match {
      case Eq(List(id, c @ Comprehension(vs, body))) if vs.forall(_.tpe == procType) => 
        val scope = bound intersect (body.freeVariables -- vs)
        acc + ((scope, id, c))
      case Eq(List(c @ Comprehension(vs, body), id)) if vs.forall(_.tpe == procType) => 
        val scope = bound intersect (body.freeVariables -- vs)
        acc + ((scope, id, c))
      case _ => acc
    }
    val defs = FormulaUtils.collectWithScope(init, process, Application(And, conjuncts))
    defs
  }

  protected val cardinalityAxioms = {
    val s = Variable("s").setType(FSet(procType))
    List(
      ForAll(List(s), Leq(Literal(0), Cardinality(s))),
      ForAll(List(s), Leq(Cardinality(s), n))
    )
  }

  protected def mkPairILP( set1: (Set[Variable], Formula, Option[Formula]),
                           set2: (Set[Variable], Formula, Option[Formula])
                         ): List[Formula] = {
    val clashing = set1._1 intersect set1._1
    def rename(set: (Set[Variable], Formula, Option[Formula])) = {
      val fresh = clashing.map(v => v -> Variable(Namer(v.name)).setType(v.tpe)).toMap
      ( set._1.map(FormulaUtils.alphaAll(fresh, _).asInstanceOf[Variable]),
        FormulaUtils.alphaAll(fresh, set._2),
        set._3.map(FormulaUtils.alphaAll(fresh, _)))
    }
    val (bound1, id1, def1) = rename(set1)
    val (bound2, id2, def2) = rename(set2)
    val params = bound1 ++ bound2
    val tt = Quantifiers.skolemify(Variable(Namer("venn_tt")).setType(Int), params)
    val tf = Quantifiers.skolemify(Variable(Namer("venn_tf")).setType(Int), params)
    val ft = Quantifiers.skolemify(Variable(Namer("venn_ft")).setType(Int), params)
    val ff = Quantifiers.skolemify(Variable(Namer("venn_ff")).setType(Int), params)
    val conjuncts = List(
      Leq(Literal(0), tt),
      Leq(Literal(0), tf),
      Leq(Literal(0), ft),
      Leq(Literal(0), ff),
      Eq(n, Plus(Plus(tt,tf),Plus(ft,ff))),
      Eq(Cardinality(id1), Plus(tt,tf)),
      Eq(Cardinality(id2), Plus(tt,ft))
    )
    def unify(v: Variable, com: Formula) = com match {
      case Comprehension(List(i), f) =>
        assert(!(f.freeVariables contains v), "capture")
        FormulaUtils.alpha(Map(i -> v), f)
      case other =>
        sys.error("expected comprehension, found: " + other)
    }
    val triggers = (def1,def2) match {
      case (Some(d1), Some(d2)) =>
        List(
          Implies(Lt(Literal(0), tt), Exists(List(vtt), And(unify(vtt,d1),unify(vtt,d2)))),
          Implies(Lt(Literal(0), tf), Exists(List(vtf), And(unify(vtt,d1),Not(unify(vtt,d2))))),
          Implies(Lt(Literal(0), ft), Exists(List(vft), And(Not(unify(vtt,d1)),unify(vtt,d2))))
        )
      case (Some(d1), None) =>
        List(
          Implies(Lt(Literal(0), Plus(tt,tf)), Exists(List(vtf), unify(vtf, d1)))
        )
      case (None, Some(d2)) =>
        List(
          Implies(Lt(Literal(0), Plus(tt,ft)), Exists(List(vft), unify(vtf, d2)))
        )
      case (None, None) => 
        Nil
    }
    FormulaUtils.getConjuncts(ForAll(params.toList, Application(And, conjuncts ::: triggers)))
  }

  protected def addILP(conjuncts: List[Formula]): List[Formula] = {
    val c1 = collectComprehensionDefinitions(conjuncts)
    val c2 = c1.map{ case (a,b,c) => (a,b,Some(c): Option[Formula]) } //def as an option, HO is not a comprehension
    val v = Variable("v").setType(procType)
    val c3 = if (conjuncts exists hasHO) c2 + ((Set(v), Application(HO, List(v)), None)) else c2
    Logger("CL", Debug, "addILP, comprehensions:\n  " + c1.mkString("\n  "))
    val ilp = for (s1 <- c3; s2 <- c3 if s1 != s2) yield mkPairILP(s1, s2)
    conjuncts ::: ilp.toList.flatten
  }

  /* add axioms for set operations */ 
  protected def addSetAxioms(conjuncts: List[Formula]): List[Formula] = {
    Logger("CL", Warning, "TODO addSetAxioms")
    conjuncts
  }
  
  def reduce(formula: Formula): Formula = {
    val n1 = normalize(formula)
    val n2 = Quantifiers.getExistentialPrefix(n1)._1
    val rawConjuncts = FormulaUtils.getConjuncts(n2)
    val conjuncts = rawConjuncts.map(f => Quantifiers.skolemize(Simplify.simplify(Simplify.pnf(f))))
    Logger("CL", Info, "reducing:\n  " + conjuncts.mkString("\n  "))
    val withILP = addILP(conjuncts)
    Logger("CL", Debug, "with ILP:\n  " + withILP.mkString("\n  "))
    val withSetAx = addSetAxioms(withILP)
    Logger("CL", Warning, "further reduction in:\n  " + withSetAx.mkString("\n  "))
    Typer(formula).get
  }
  
}
