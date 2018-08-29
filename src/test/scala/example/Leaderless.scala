package example

import psync._
import psync.Time._
import psync.formula._
import psync.macros.Macros._
import psync.utils.serialization._

class Leaderless extends Algorithm[ConsensusIO, LLProcess] {

  val spec = TrivialSpec

  def process = new LLProcess()

  def dummyIO = new ConsensusIO{
    val initialValue = 0
    def decide(value: Int) { }
  }
}

class LLProcess extends Process[ConsensusIO]{

  //variables
  var x = 0
  var ts = new Time(-1) // TODO: max vote?
  var trying = false
  var commit = false
  var proposal = 0
  var decision = -1 //TODO as ghost
  var decided = false
  //
  var callback: ConsensusIO = null

  def coord: ProcessID = new ProcessID((r / 4 % n).toShort)

  def init(io: ConsensusIO) {
    callback = io
    x = io.initialValue
    ts = -1 // TODO: last vote?
    proposal = 0
    decided = false
    trying = true
    commit = false
  }

  val rounds = phase(

    // The phase-change round
    new Round[(Int,Time)]{

      def send(): Map[ProcessID,(Int, Time)] = {
        Map(coord -> (x, ts))
      }

      override def expectedNbrMessages = { // if this happens before timeout, increment round
        if (r.toInt == 0) 1
        else n/2 + 1
      }

      def update(mailbox: Map[ProcessID,(Int, Time)]) {
        assert(r.toInt % 4 == 0)
        if (
          mailbox.size > n/2 ||
            (r.toInt == 0 && mailbox.nonEmpty)) {
          // let θ be one of the largest θ from 〈ν, θ〉received
          // vote(p) := one ν such that 〈ν, θ〉 is received
          proposal = mailbox.maxBy(_._2._2)._2._1
          trying = true
          //assert(proposal != 0, mailbox.mkString(", "))
        }
      }

    },

    // the conciliation round
    new Round[Int]{

      def send(): Map[ProcessID,Int] = {
        if (trying) {
          broadcast(proposal)
        } else {
          Map.empty[ProcessID,Int]
        }
      }

      override def expectedNbrMessages = 1

      def update(mailbox: Map[ProcessID,Int]) {
        proposal = mailbox.foldLeft(proposal)( (acc, v) => math.min(acc, v._2) )
        //assert(x != 0)
      }

    },

    // The proposal round (simlulates a leader proposing a unique value)
    new Round[Int]{

      def send(): Map[ProcessID,Int] = {
        if ( trying ) {
          broadcast(proposal)
        } else {
          Map.empty[ProcessID,Int]
        }
      }

      override def expectedNbrMessages = n/2 + 1

      def update(mailbox: Map[ProcessID,Int]) {
        if (mailbox.count{ case (k, msg) => msg == proposal } > n/2) { // TODO: any value reaching quorum threshold is fine
          commit = true
          x = proposal
          ts = r/4
        }
        else commit = false
      }

    },

    // This is the voting round
    new Round[Int]{

      def send(): Map[ProcessID, Int] = {
        if (commit) {
          broadcast(proposal)
        } else {
          Map.empty[ProcessID,Int]
        }
      }

      override def expectedNbrMessages = n/2 + 1

      def update(mailbox: Map[ProcessID,Int]) {
        if (mailbox.count{ case (k, msg) => msg == proposal } > n/2) {
          //assert(proposal != 0)
          callback.decide(proposal)
          decision = proposal
          decided = true
          exitAtEndOfRound()
        }
      }

    }

  )

}
