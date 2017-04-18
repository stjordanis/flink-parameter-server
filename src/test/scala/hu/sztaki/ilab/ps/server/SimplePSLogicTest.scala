package hu.sztaki.ilab.ps.server

import hu.sztaki.ilab.ps.ParameterServer
import org.scalatest._
import prop._

class SimplePSLogicTest extends FlatSpec with PropertyChecks with Matchers {
  type P = Int
  type PSOut = (Int, Int)

  "If a pull is not prevented by push it" should "throw exeption" in {
    val testPsLogic = new SimplePSLogic[P]((x: Int) => x, ((x: P, y: P) => x))
    a[IllegalStateException] should be thrownBy {
      testPsLogic.onPushRecv(42, 42, new ParameterServer[P, PSOut] {
        override def answerPull(id: P, value: P, workerPartitionIndex: P): Unit = {}

        override def output(out: (P, P)): Unit = {}
      })
    }
  }

  "Model's state initilaization" should "be working" in {
    val testPsLogic = new SimplePSLogic[P]((x: Int) => 23, (x: P, y: P) => y)
    testPsLogic.onPullRecv(42, 42, new ParameterServer[P, PSOut] {
      override def answerPull(id: P, value: P, workerPartitionIndex: P): Unit = {}

      override def output(out: (P, P)): Unit = {}
    })
    testPsLogic.params(42) should be (23)
  }

  "If a pull is prevented by initial a model it" should "be updated after a push" in {
    val testPsLogic = new SimplePSLogic[P]((x: Int) => 0, (x: P, y: P) => y)
    testPsLogic.onPullRecv(42, 42, new ParameterServer[P, PSOut] {
      override def answerPull(id: P, value: P, workerPartitionIndex: P): Unit = {}

      override def output(out: (P, P)): Unit = {}
    })
    val mockPS = new ParameterServer[P, PSOut] {
      var x = (0, 0)

      override def answerPull(id: P, value: P, workerPartitionIndex: P): Unit = {}

      override def output(out: (P, P)): Unit = {
        x = out
      }
    }
    testPsLogic.onPushRecv(42, 23, mockPS)
    mockPS.x should be(42, 23)
  }

}