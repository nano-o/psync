package round.verification

import Utils._

import round.formula._

//a wrapper around a formula, old/primed variables, ...

class RoundTransitionRelation(val send: Formula,
                              val mailboxSend: Variable,
                              val update: Formula,
                              val mailboxUpdt: Variable,
                              val old: List[Variable],
                              val local: List[Variable],
                              val primed: List[Variable]) {

  //TODO:
  //  send → ∀ i. send(i) 
  //  update → ∀ i. update(i)
  //and skolemize the locals

  //link mailboxes with HO:
  //  ∀ i j v. (i, v) ∈ mailboxUpdt(j) ⇔ (i ∈ HO(j) ∧ (j, v) ∈ mailboxSend(i))
  val mailboxLink = {
    val i = procI
    val j = procJ
    val v = Variable("_v") //TODO get type from mailbox
    val iv = Application(Tuple, List(v, i))
    val jv = Application(Tuple, List(v, j))
    val mi = skolemify(mailboxSend, i)
    val mj = skolemify(mailboxUpdt, j)
    val ho = In(i, skolemify(Variable("HO"), j))
    ForAll(List(i, j, v), Eq(In(iv, mj), And(ho, In(jv, mi))))
  }
  
  class InlinePost(aux: Map[String, AuxiliaryMethod], vars: Set[Variable], i: Variable) extends Transformer {
    override def transform(f: Formula): Formula = {
      f match {
        case Eq(List(retVal, Application(UnInterpretedFct(fct, _, tParams), args))) if aux contains fct =>
          val auxDef = aux(fct).applyType(tParams)
          super.transform(localize(vars, i, auxDef.makePostAssume(args, retVal)))
        case other => other
      }
    }
  }

  def makeFullTr(vars: Set[Variable], aux: Map[String, AuxiliaryMethod]): Formula = {
    assert(old forall ((vars + mailboxSend + mailboxUpdt) contains _))
    val localVars = vars ++ local ++ old ++ primed + mailboxSend + mailboxUpdt
    val i = procI //TODO check it is not captured/ing
    val sendLocal = localize(localVars, i, send)
    val updateLocal = localize(localVars, i, update)
    val allParts = And(sendLocal, updateLocal)
    val inliner = new InlinePost(aux, localVars, i)
    And(mailboxLink, ForAll(List(i), inliner.transform(allParts)))
  }
  
  val primedSubst: Map[UnInterpretedFct, UnInterpretedFct] = {
    (old zip primed).map{ case (o,p) => (skolemify(o), skolemify(p)) }.toMap
  }
  
  def primeFormula(f: Formula) = {
    removeOldPrefix(FormulaUtils.mapSymbol({
      case f @ UnInterpretedFct(_,_,_) => primedSubst.getOrElse(f,f)
      case f => f
    }, f))
  }

}


//this is the single process version
class TransitionRelation(_tr: Formula,
                         val old: List[Variable],
                         val local: List[Variable],
                         val primed: List[Variable]) {

  val tr = FormulaUtils.purify(_tr)

  /* change the var so the formula refer to the primed vars */
  def primeFormula(f: Formula) = {
    val subst = old.zip(primed).foldLeft(Map.empty[Variable,Variable])(_ + _)
    f.alpha(subst)
  }

  class InlinePost(aux: Map[String, AuxiliaryMethod]) extends Transformer {
    override def transform(f: Formula): Formula = {
      f match {
        case Eq(List(retVal, Application(UnInterpretedFct(fct, _, tParams), args))) if aux contains fct =>
          val auxDef = aux(fct).applyType(tParams)
          super.transform(auxDef.makePostAssume(args, retVal))
        case other => other
      }
    }
  }

  /* the formula with the postconditions */
  def addAuxiliary(aux: Map[String, AuxiliaryMethod]): Formula = {
    val inliner = new InlinePost(aux)
    inliner.transform(tr)
  }

  /* returns a list of precondition to test */
  def auxPrecondition(aux: Map[String, AuxiliaryMethod]): List[(Formula, Formula)] = {
    sys.error("TODO ...")
  }
  
  def report = sys.error("TODO")

}
