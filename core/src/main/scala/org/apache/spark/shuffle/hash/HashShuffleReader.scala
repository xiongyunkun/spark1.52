/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.hash

import org.apache.spark._
import org.apache.spark.serializer.Serializer
import org.apache.spark.shuffle.{BaseShuffleHandle, ShuffleReader}
import org.apache.spark.storage.{BlockManager, ShuffleBlockFetcherIterator}
import org.apache.spark.util.CompletionIterator
import org.apache.spark.util.collection.ExternalSorter

private[spark] class HashShuffleReader[K, C](
    handle: BaseShuffleHandle[K, _, C],
    startPartition: Int,
    endPartition: Int,
    context: TaskContext,
    blockManager: BlockManager = SparkEnv.get.blockManager,
    mapOutputTracker: MapOutputTracker = SparkEnv.get.mapOutputTracker)
  extends ShuffleReader[K, C] with Logging {

  require(endPartition == startPartition + 1,
    "Hash shuffle currently only supports fetching one partition")

  private val dep = handle.dependency

  /** Read the combined key-values for this reduce task
    * 读取此reduce任务的组合键值*/
  override def read(): Iterator[Product2[K, C]] = {
    /**
     * 处理步骤如下:
     * 1)从远程节点或者本地读取中间计算结果
     * 2)对InterruptibleIterator执行聚合
     * 3)对InterruptibleIterator排序,由于使用ExternalSorter的inertAll
     */
    val blockFetcherItr = new ShuffleBlockFetcherIterator(
      context,
      blockManager.shuffleClient,
      blockManager,
      mapOutputTracker.getMapSizesByExecutorId(handle.shuffleId, startPartition),
      // Note: we use getSizeAsMb when no suffix is provided for backwards compatibility
      //在Shuffle的时候,每个Reducer任务获取缓存数据指定大小(以兆字节为单位) 
      SparkEnv.get.conf.getSizeAsMb("spark.reducer.maxSizeInFlight", "48m") * 1024 * 1024)

    // Wrap the streams for compression based on configuration
    //根据配置将流包装成压缩
    val wrappedStreams = blockFetcherItr.map { case (blockId, inputStream) =>
      blockManager.wrapForCompression(blockId, inputStream)
    }

    val ser = Serializer.getSerializer(dep.serializer)
    val serializerInstance = ser.newInstance()

    // Create a key/value iterator for each stream
    //为每个流创建一个键/值迭代器
    val recordIter = wrappedStreams.flatMap { wrappedStream =>
      // Note: the asKeyValueIterator below wraps a key/value iterator inside of a
      // NextIterator. The NextIterator makes sure that close() is called on the
      // underlying InputStream when all records have been read.
      //注意：下面的asKeyValueIterator将一个键/值迭代器包含在NextIterator的内部。
      // NextIterator确保在读取所有记录时对底层InputStream调用close（）。
      serializerInstance.deserializeStream(wrappedStream).asKeyValueIterator
    }

    // Update the context task metrics for each record read.
    //更新读取的每个记录的上下文任务指标
    val readMetrics = context.taskMetrics.createShuffleReadMetricsForDependency()
    val metricIter = CompletionIterator[(Any, Any), Iterator[(Any, Any)]](
      recordIter.map(record => {
        readMetrics.incRecordsRead(1)
        record
      }),
      context.taskMetrics().updateShuffleReadMetrics())

    // An interruptible iterator must be used here in order to support task cancellation
    //必须在此处使用可中断的迭代器才能支持任务取消
    val interruptibleIter = new InterruptibleIterator[(Any, Any)](context, metricIter)
    //对InterruptibleIterator执行聚合
    val aggregatedIter: Iterator[Product2[K, C]] = if (dep.aggregator.isDefined) {
      if (dep.mapSideCombine) {//是否需要在worker端进行combine操作聚合
        // We are reading values that are already combined
        //我们正在读取已经组合的值
        val combinedKeyValuesIterator = interruptibleIter.asInstanceOf[Iterator[(K, C)]]
        dep.aggregator.get.combineCombinersByKey(combinedKeyValuesIterator, context)
      } else {//只需要Reducer端的聚合
        // We don't know the value type, but also don't care -- the dependency *should*
        // have made sure its compatible w/ this aggregator, which will convert the value
        // type to the combined type C
        //我们不知道值类型,但也不在意 - 依赖*应该*确保其兼容w /这个聚合器,这将把值类型转换为组合类型C
        val keyValuesIterator = interruptibleIter.asInstanceOf[Iterator[(K, Nothing)]]
        dep.aggregator.get.combineValuesByKey(keyValuesIterator, context)
      }
    } else {//无需聚合操作
      require(!dep.mapSideCombine, "Map-side combine without Aggregator specified!")
      interruptibleIter.asInstanceOf[Iterator[Product2[K, C]]]
    }

    // Sort the output if there is a sort ordering defined.
    //如果定义了排序顺序,则对输出进行排序。
    //对InterruptibleIterator排序,使用insertAll
    dep.keyOrdering match {//判断是否需要排序
      case Some(keyOrd: Ordering[K]) => //对于需要排序的情况
        // Create an ExternalSorter to sort the data. Note that if spark.shuffle.spill is disabled,
        // the ExternalSorter won't spill to disk.
        //创建一个ExternalSorter来对数据进行排序。
        // 请注意,如果spark.shuffle.spill被禁用,ExternalSorter不会溢出到磁盘。
        //使用ExternalSorter进行排序,注意如果Spark.Shuffle.spill是false那么数据是不会写入到硬盘的
        val sorter = new ExternalSorter[K, C, C](ordering = Some(keyOrd), serializer = Some(ser))
        sorter.insertAll(aggregatedIter)
        context.taskMetrics().incMemoryBytesSpilled(sorter.memoryBytesSpilled)
        context.taskMetrics().incDiskBytesSpilled(sorter.diskBytesSpilled)
        context.internalMetricsToAccumulators(
          InternalAccumulator.PEAK_EXECUTION_MEMORY).add(sorter.peakMemoryUsedBytes)
        sorter.iterator
      case None => //不需要排序
        aggregatedIter
    }
  }
}
