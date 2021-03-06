package hu.sztaki.ilab.ps.passive.aggressive

import breeze.linalg._
import hu.sztaki.ilab.ps.client.receiver.SimpleWorkerReceiver
import hu.sztaki.ilab.ps.client.sender.SimpleWorkerSender
import hu.sztaki.ilab.ps.entities.{PSToWorker, Pull, Push, WorkerToPS}
import hu.sztaki.ilab.ps.passive.aggressive.algorithm.PassiveAggressiveAlgorithm
import hu.sztaki.ilab.ps.passive.aggressive.algorithm.PassiveAggressiveParameterInitializer._
import hu.sztaki.ilab.ps.server.{RangePSLogicWithClose, SimplePSLogicWithClose}
import hu.sztaki.ilab.ps.server.receiver.SimplePSReceiver
import hu.sztaki.ilab.ps.server.sender.SimplePSSender
import hu.sztaki.ilab.ps.{FlinkParameterServer, ParameterServerClient, WorkerLogic}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.scala._
//import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class PassiveAggressiveParameterServer


object PassiveAggressiveParameterServer {

//  private val log = LoggerFactory.getLogger(classOf[PassiveAggressiveParameterServer])

  type FeatureId = Int

  type LabeledVector[LabelType] = (SparseVector[Double], LabelType)
  type UnlabeledVector = (Long, SparseVector[Double])
  type OptionLabeledVector[LabelType] = Either[LabeledVector[LabelType], UnlabeledVector]

  /**
    * Applies online multiclass classification for a [[org.apache.flink.streaming.api.scala.DataStream]] of sparse
    * vectors. For vectors with labels, it updates the model "passive-aggressively",
    * for vectors without label predicts its labels based on the model.
    *
    * Labels should be indexed from 0.
    *
    * Note that the order could be mixed, i.e. it's possible to predict based on some model parameters updated, while
    * others not.
    *
    * @param inputSource
    * [[org.apache.flink.streaming.api.scala.DataStream]] of labelled and unlabelled vector.
    * The label is marked with an [[Option]].
    * @param workerParallelism
    * Number of worker instances for Parameter Server.
    * @param psParallelism
    * Number of Parameter Server instances.
    * @param passiveAggressiveMethod
    * Method for Passive Aggressive training.
    * @param pullLimit
    * Limit of unanswered pulls at a worker instance.
    * @param labelCount
    * Number of possible labels.
    * @param iterationWaitTime
    * Time to wait for new messages at worker. If set to 0, the job will run infinitely.
    * PS is implemented with a Flink iteration, and Flink does not know when the iteration finishes,
    * so this is how the job can finish.
    * @return
    * Stream of Parameter Server model updates and predicted values.
    */
  private def transformMulticlassGen[VecId: TypeInformation](model: Option[DataStream[(Int, Vector[Double])]] = None)
                                                            (inputSource: DataStream[OptionLabeledVector[Int]],
                                                             workerParallelism: Int,
                                                             psParallelism: Int,
                                                             passiveAggressiveMethod: PassiveAggressiveAlgorithm[Vector[Double], Int, CSCMatrix[Double]],
                                                             pullLimit: Int,
                                                             labelCount: Int,
                                                             featureCount: Int,
                                                             rangePartitioning: Boolean,
                                                             iterationWaitTime: Long)
                                                            (implicit ev:
                                                            OptionLabeledVectorWithId[OptionLabeledVector[Int], VecId, Int])
  : DataStream[Either[(VecId, Int), (FeatureId, Vector[Double])]] = {
    val multiModelBuilder = new ModelBuilder[Vector[Double], CSCMatrix[Double]] {
      override def buildModel(params: Iterable[(FeatureId, Vector[Double])],
                              featureCount: Int): CSCMatrix[Double] = {
        val builder = new CSCMatrix.Builder[Double](featureCount, labelCount)
        params.foreach { case (i, vector) => vector.foreachPair((j, v) => builder.add(i, j, v)) }

        builder.result
      }
    }

    transformGeneric[Vector[Double], Int, CSCMatrix[Double], OptionLabeledVector[Int], VecId](model)(
      initMulti(labelCount), _ + _, multiModelBuilder
    )(
      inputSource, workerParallelism, psParallelism, passiveAggressiveMethod,
      pullLimit, labelCount, featureCount, rangePartitioning, iterationWaitTime
    )
  }

  /**
    * Applies online multiclass classification for a [[org.apache.flink.streaming.api.scala.DataStream]] of sparse
    * vectors. For vectors with labels, it updates the model "passive-aggressively",
    * for vectors without label predicts its labels based on the model.
    *
    * Unlabelled vectors are marked by a Long identifier which is used for giving predictions.
    *
    * Labels should be indexed from 0.
    *
    * Note that the order could be mixed, i.e. it's possible to predict based on some model parameters updated, while
    * others not.
    *
    * @param inputSource
    * [[org.apache.flink.streaming.api.scala.DataStream]] of labelled and unlabelled vector.
    * The label is marked with an [[Option]].
    * @param workerParallelism
    * Number of worker instances for Parameter Server.
    * @param psParallelism
    * Number of Parameter Server instances.
    * @param passiveAggressiveMethod
    * Method for Passive Aggressive training.
    * @param pullLimit
    * Limit of unanswered pulls at a worker instance.
    * @param labelCount
    * Number of possible labels.
    * @param iterationWaitTime
    * Time to wait for new messages at worker. If set to 0, the job will run infinitely.
    * PS is implemented with a Flink iteration, and Flink does not know when the iteration finishes,
    * so this is how the job can finish.
    * @return
    * Stream of Parameter Server model updates and predicted values.
    */
  def transformMulticlassWithLongId(model: Option[DataStream[(Int, Vector[Double])]] = None)
                                   (inputSource: DataStream[OptionLabeledVector[Int]],
                                    workerParallelism: Int,
                                    psParallelism: Int,
                                    passiveAggressiveMethod: PassiveAggressiveAlgorithm[Vector[Double], Int, CSCMatrix[Double]],
                                    pullLimit: Int,
                                    labelCount: Int,
                                    featureCount: Int,
                                    rangePartitioning: Boolean,
                                    iterationWaitTime: Long)
  : DataStream[Either[(Long, Int), (FeatureId, Vector[Double])]] =
    transformMulticlassGen[Long](model)(inputSource, workerParallelism, psParallelism,
      passiveAggressiveMethod, pullLimit, labelCount, featureCount, rangePartitioning, iterationWaitTime)

  /**
    * Applies online multiclass classification for a [[org.apache.flink.streaming.api.scala.DataStream]] of sparse
    * vectors. For vectors with labels, it updates the model "passive-aggressively",
    * for vectors without label predicts its labels based on the model.
    *
    * Labels should be indexed from 0.
    *
    * Note that the order could be mixed, i.e. it's possible to predict based on some model parameters updated, while
    * others not.
    *
    * @param inputSource
    * [[org.apache.flink.streaming.api.scala.DataStream]] of labelled and unlabelled vector.
    * The label is marked with an [[Option]].
    * @param workerParallelism
    * Number of worker instances for Parameter Server.
    * @param psParallelism
    * Number of Parameter Server instances.
    * @param passiveAggressiveMethod
    * Method for Passive Aggressive training.
    * @param pullLimit
    * Limit of unanswered pulls at a worker instance.
    * @param labelCount
    * Number of possible labels.
    * @param iterationWaitTime
    * Time to wait for new messages at worker. If set to 0, the job will run infinitely.
    * PS is implemented with a Flink iteration, and Flink does not know when the iteration finishes,
    * so this is how the job can finish.
    * @return
    * Stream of Parameter Server model updates and predicted values.
    */
  def transformMulticlass(model: Option[DataStream[(Int, Vector[Double])]] = None)
                         (inputSource: DataStream[OptionLabeledVector[Int]],
                          workerParallelism: Int,
                          psParallelism: Int,
                          passiveAggressiveMethod: PassiveAggressiveAlgorithm[Vector[Double], Int, CSCMatrix[Double]],
                          pullLimit: Int,
                          labelCount: Int,
                          featureCount: Int,
                          rangePartitioning: Boolean,
                          iterationWaitTime: Long)
  : DataStream[Either[(SparseVector[Double], Int), (FeatureId, Vector[Double])]] =
    transformMulticlassGen[SparseVector[Double]](model)(inputSource, workerParallelism, psParallelism,
      passiveAggressiveMethod, pullLimit, labelCount, featureCount, rangePartitioning, iterationWaitTime)

  /**
    * Applies online binary classification for a [[org.apache.flink.streaming.api.scala.DataStream]] of sparse vectors.
    * For vectors with labels, it updates the model "passive-aggressively",
    * for vectors without label predicts its labels based on the model.
    *
    * Note that the order could be mixed, i.e. it's possible to predict based on some model parameters updated, while
    * others not.
    *
    * @param inputSource
    * [[org.apache.flink.streaming.api.scala.DataStream]] of labelled and unlabelled vector. The label is marked with an
    * [[Option]].
    * @param workerParallelism
    * Number of worker instances for Parameter Server.
    * @param psParallelism
    * Number of Parameter Server instances.
    * @param passiveAggressiveMethod
    * Method for Passive Aggressive training.
    * @param pullLimit
    * Limit of unanswered pulls at a worker instance.
    * @param iterationWaitTime
    * Time to wait for new messages at worker. If set to 0, the job will run infinitely.
    * PS is implemented with a Flink iteration, and Flink does not know when the iteration finishes,
    * so this is how the job can finish.
    * @return
    * Stream of Parameter Server model updates and predicted values.
    */
  def transformBinary(model: Option[DataStream[(Int, Double)]] = None)
                     (inputSource: DataStream[OptionLabeledVector[Boolean]],
                      workerParallelism: Int,
                      psParallelism: Int,
                      passiveAggressiveMethod: PassiveAggressiveAlgorithm[Double, Boolean, Vector[Double]],
                      pullLimit: Int,
                      featureCount: Int,
                      rangePartitioning: Boolean,
                      iterationWaitTime: Long)
  : DataStream[Either[(SparseVector[Double], Boolean), (FeatureId, Double)]] = {
    val labelCount = 1

    val binaryModelBuilder = new ModelBuilder[Double, Vector[Double]] {
      override def buildModel(params: Iterable[(FeatureId, Double)],
                              featureCount: Int): Vector[Double] = {
        val builder = new VectorBuilder[Double](featureCount)
        params.foreach { case (i, v) => builder.add(i, v) }
        builder.toSparseVector(keysAlreadyUnique = true)
      }
    }

    transformGeneric[Double, Boolean, Vector[Double], OptionLabeledVector[Boolean], SparseVector[Double]](model)(
      initBinary, _ + _, binaryModelBuilder
    )(
      inputSource, workerParallelism, psParallelism, passiveAggressiveMethod,
      pullLimit, labelCount, featureCount, rangePartitioning, iterationWaitTime
    )
  }

  private def transformGeneric
  [Param, Label, Model, Vec, VecId](model: Option[DataStream[(Int, Param)]] = None)
                                   (init: Int => Param,
                                    add: (Param, Param) => Param,
                                    modelBuilder: ModelBuilder[Param, Model])
                                   (inputSource: DataStream[Vec],
                                    workerParallelism: Int,
                                    psParallelism: Int,
                                    passiveAggressiveMethod: PassiveAggressiveAlgorithm[Param, Label, Model],
                                    pullLimit: Int,
                                    labelCount: Int,
                                    featureCount: Int,
                                    // TODO avoid using boolean
                                    rangePartitioning: Boolean,
                                    iterationWaitTime: Long)
                                   (implicit
                                    tiParam: TypeInformation[Param],
                                    tiLabel: TypeInformation[Label],
                                    tiVec: TypeInformation[Vec],
                                    tiVecId: TypeInformation[VecId],
                                    ev: OptionLabeledVectorWithId[Vec, VecId, Label])
  : DataStream[Either[(VecId, Label), (FeatureId, Param)]] = {

    val serverLogic =
      if (rangePartitioning) {
        new RangePSLogicWithClose[Param](featureCount, init, add)
      } else {
        new SimplePSLogicWithClose[Int, Param](init, add)
      }

    val paramPartitioner: WorkerToPS[Int, Param] => Int =
      if (rangePartitioning) {
        rangePartitionerPS(featureCount)(psParallelism)
      } else {
        val partitonerFunction = (paramId: Int) => Math.abs(paramId) % psParallelism
        val p: WorkerToPS[Int, Param] => Int = {
          case WorkerToPS(_, msg) => msg match {
            case Left(Pull(paramId)) => partitonerFunction(paramId)
            case Right(Push(paramId, _)) => partitonerFunction(paramId)
          }
        }
        p
      }

    val workerLogic = WorkerLogic.addPullLimiter( // adding pull limiter to avoid iteration deadlock
      new WorkerLogic[Vec, Int, Param, (VecId, Label)] {

        val paramWaitingQueue = new mutable.HashMap[
          Int,
          mutable.Queue[(Vec, ArrayBuffer[(Int, Param)])]
          ]()

        override def onRecv(data: Vec,
                            ps: ParameterServerClient[Int, Param, (VecId, Label)]): Unit = {
          // pulling parameters and buffering data while waiting for parameters

          val vector = ev.vector(data)

          // buffer to store the already received parameters
          val waitingValues = new ArrayBuffer[(Int, Param)]()
          //            vector.activeKeysIterator
          // based on the official recommendation the activeIterator was optimized the following way:
          //    https://github.com/scalanlp/breeze/wiki/Data-Structures#efficiently-iterating-over-a-sparsevector
          (0 until vector.activeSize).map(offset => vector.indexAt(offset))
            .foreach(k => {
              paramWaitingQueue.getOrElseUpdate(k,
                mutable.Queue[(Vec, ArrayBuffer[(Int, Param)])]())
                .enqueue((data, waitingValues))
              ps.pull(k)
            })
        }

        override def onPullRecv(paramId: Int,
                                modelValue: Param,
                                ps: ParameterServerClient[Int, Param, (VecId, Label)]) {
          // store the received parameters and train/predict when all corresponding parameters arrived for a vector
          val q = paramWaitingQueue(paramId)
          val (restedData, waitingValues) = q.dequeue()
          waitingValues += paramId -> modelValue

          val vector = ev.vector(restedData)
          if (waitingValues.size == vector.activeSize) {
            // we have received all the parameters

            val model: Model = modelBuilder.buildModel(waitingValues, vector.length)

            ev.label(restedData) match {
              case Some(label) =>
                // we have a labelled vector, so we update the model
                passiveAggressiveMethod.delta(vector, model, label)
                  .foreach {
                    case (i, v) => ps.push(i, v)
                  }
              case None =>
                // we have an unlabelled vector, so we predict based on the model
                ps.output(ev.id(restedData), passiveAggressiveMethod.predict(vector, model))
            }
          }
          if (q.isEmpty) paramWaitingQueue.remove(paramId)
        }

      }, pullLimit)


    val wInPartition: PSToWorker[Int, Param] => Int = {
      case PSToWorker(workerPartitionIndex, _) => workerPartitionIndex
    }

    val modelUpdates = model match {
      case Some(m) => FlinkParameterServer.transformWithModelLoad[
        Vec, Int, Param, (FeatureId, Param),
        (VecId, Label)](m)(
        inputSource, workerLogic, serverLogic,
        paramPartitioner,
        wInPartition,
        workerParallelism,
        psParallelism,
        iterationWaitTime)
      case None => FlinkParameterServer.transform[
        Vec, Int, Param, (FeatureId, Param),
        (VecId, Label), PSToWorker[Int, Param], WorkerToPS[Int, Param]](
        inputSource, workerLogic, serverLogic,
        paramPartitioner,
        wInPartition,
        workerParallelism,
        psParallelism,
        new SimpleWorkerReceiver[Int, Param](),
        new SimpleWorkerSender[Int, Param](),
        new SimplePSReceiver[Int, Param](),
        new SimplePSSender[Int, Param](),
        iterationWaitTime)
    }
    modelUpdates
  }

  def rangePartitionerPS[P](featureCount: Int)(psParallelism: Int): (WorkerToPS[Int, P]) => Int = {
    val partitionSize = Math.ceil(featureCount.toDouble / psParallelism).toInt
    val partitonerFunction = (paramId: Int) => Math.abs(paramId) / partitionSize

    val paramPartitioner: WorkerToPS[Int, P] => Int = {
      case WorkerToPS(_, msg) => msg match {
        case Left(Pull(paramId)) => partitonerFunction(paramId)
        case Right(Push(paramId, _)) => partitonerFunction(paramId)
      }
    }

    paramPartitioner
  }

  /**
    * Generic model builder for binary and multiclass cases.
    *
    * @tparam Param
    * Type of Parameter Server parameter.
    * @tparam Model
    * Type of the model.
    */
  private trait ModelBuilder[Param, Model] extends Serializable {

    /**
      * Creates a model out of single parameters.
      *
      * @param params
      * Parameters.
      * @param featureCount
      * Number of features.
      * @return
      * Model.
      */
    def buildModel(params: Iterable[(Int, Param)], featureCount: Int): Model

  }

  private trait OptionLabeledVectorWithId[V, Id, Label] extends Serializable {
    def vector(v: V): SparseVector[Double]

    def label(v: V): Option[Label]

    def id(v: V): Id
  }

  private abstract class OptionLabeledVectorWithIdImpl[Label, Id]
    extends OptionLabeledVectorWithId[OptionLabeledVector[Label], Id, Label] {
      override def vector(v: OptionLabeledVector[Label]): SparseVector[Double] = v match {
        case Left((vec, _)) => vec
        case Right((_, vec)) => vec
      }

      override def label(v: OptionLabeledVector[Label]): Option[Label] = v match {
        case Left((_, lab)) => Some(lab)
        case _ => None
      }
    }

  private implicit def optionLabeledMultiEvLong:
  OptionLabeledVectorWithId[OptionLabeledVector[Int], Long, Int] =
    optionLabeledVecEvLongId[Int]

  private implicit def optionLabeledMultiEv:
  OptionLabeledVectorWithId[OptionLabeledVector[Int], SparseVector[Double], Int] =
    optionLabeledVecEv[Int]

  private implicit def optionLabeledBinaryEv:
  OptionLabeledVectorWithId[OptionLabeledVector[Boolean], SparseVector[Double], Boolean] =
    optionLabeledVecEv[Boolean]

  private implicit def optionLabeledVecEv[Label]:
  OptionLabeledVectorWithId[OptionLabeledVector[Label], SparseVector[Double], Label] =
    new OptionLabeledVectorWithIdImpl[Label, SparseVector[Double]] {

      override def id(v: OptionLabeledVector[Label]): SparseVector[Double] = vector(v)
    }

  private implicit def optionLabeledVecEvLongId[Label]:
  OptionLabeledVectorWithId[OptionLabeledVector[Label], Long, Label] =
    new OptionLabeledVectorWithIdImpl[Label, Long] {

      override def id(v: OptionLabeledVector[Label]): Long = v match {
        case Right((id, _)) => id
        case Left(_) => throw new IllegalArgumentException("Only unlabelled vectors have id.")
      }
    }
}

