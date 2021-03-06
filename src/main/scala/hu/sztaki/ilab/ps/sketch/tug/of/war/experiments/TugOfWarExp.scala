package hu.sztaki.ilab.ps.sketch.tug.of.war.experiments

import hu.sztaki.ilab.ps.sketch.tug.of.war.TugOfWar
import hu.sztaki.ilab.ps.sketch.utils.TweetReader
import org.apache.flink.core.fs.FileSystem
import org.apache.flink.streaming.api.scala._

class TugOfWarExp {



}

object TugOfWarExp {

  def main(args: Array[String]): Unit = {

    val srcFile = args(0)
    val searchWords = args(1)
    val delimiter = args(2)
    val modelFile = args(3)
    val workerParallelism = args(4).toInt
    val psParallelism = args(5).toInt
    val iterationWaitTime = args(6).toLong
    val numHashes = args(7).toInt

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    val words = scala.io.Source.fromFile(searchWords).getLines.toList

    val src = env
      .readTextFile(srcFile)
        .flatMap(new TweetReader(delimiter, words))

    TugOfWar.tugOfWar(
      src,
      numHashes,
      workerParallelism,
      psParallelism,
      iterationWaitTime
    ).map(value => s"${value._1}:${value._2.mkString(",")}")
      .writeAsText(modelFile, FileSystem.WriteMode.OVERWRITE)

    env.execute()

  }

}

