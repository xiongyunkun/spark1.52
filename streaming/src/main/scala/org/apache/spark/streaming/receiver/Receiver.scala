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

package org.apache.spark.streaming.receiver

import java.nio.ByteBuffer

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._

import org.apache.spark.storage.StorageLevel
import org.apache.spark.annotation.DeveloperApi

/**
 * :: DeveloperApi ::
 * Spark Streaming 内置的输入流接收器或者用户自定义的接收器,用户于从数据源接收源源不断的数据流
 * Abstract class of a receiver that can be run on worker nodes to receive external data. A
 * 可以在工作节点上运行的接收外部数据的接收器的抽象类,
 * custom receiver can be defined by defining the functions `onStart()` and `onStop()`. `onStart()`
 * should define the setup steps necessary to start receiving data,
 * 一个自定义的接收器可以通过定义的功能'onstart()'和'onstop()'定义。'onstart()'应该定义设置的必要步骤开始接收数据
 * and `onStop()` should define the cleanup steps necessary to stop receiving data.
 * 和'onstop()'应该定义清理的必要步骤停止接收数据。
 * Exceptions while receiving can be handled either by restarting the receiver with `restart(...)`
 * or stopped completely by `stop(...)` or  A custom receiver in Scala would look like this.
 * 异常而接收可以处理,通过重新启动接收器与"restart(…)"或停止完全由"stop(...)"或在Scala中自定义接收器看起来像这样
 * {{{
 *  class MyReceiver(storageLevel: StorageLevel) extends NetworkReceiver[String](storageLevel) {
 *      def onStart() {
 *          // Setup stuff (start threads, open sockets, etc.) to start receiving data.
 *          // Must start new thread to receive data, as onStart() must be non-blocking.
 *
 *          // Call store(...) in those threads to store received data into Spark's memory.
 *
 *          // Call stop(...), restart(...) or reportError(...) on any thread based on how
 *          // different errors needs to be handled.
 *
 *          // See corresponding method documentation for more details
 *      }
 *
 *      def onStop() {
 *          // Cleanup stuff (stop threads, close sockets, etc.) to stop receiving data.
 *      }
 *  }
 * }}}
 *
 * Receiver 的总指挥 ReceiverTracker 分发多个 job（每个 job 有 1 个 task, 到多个 executor 上分别启动ReceiverSupervisor 实例
 * A custom receiver in Java would look like this. 
 * {{{
 * class MyReceiver extends Receiver<String> {
 *     public MyReceiver(StorageLevel storageLevel) {
 *         super(storageLevel);
 *     }
 *
 *     public void onStart() {
 *          // Setup stuff (start threads, open sockets, etc.) to start receiving data.
 *          // Must start new thread to receive data, as onStart() must be non-blocking.
 *
 *          // Call store(...) in those threads to store received data into Spark's memory.
 *
 *          // Call stop(...), restart(...) or reportError(...) on any thread based on how
 *          // different errors needs to be handled.
 *
 *          // See corresponding method documentation for more details
 *     }
 *
 *     public void onStop() {
 *          // Cleanup stuff (stop threads, close sockets, etc.) to stop receiving data.
 *     }
 * }
 * }}}
 */
@DeveloperApi
/**
 * 用于从数据源接收源源不断的数据流,Receiver是一条一条接收数据
 */
abstract class Receiver[T](val storageLevel: StorageLevel) extends Serializable {

  /**
   * This method is called by the system when the receiver is started. This function
   * must initialize all resources (threads, buffers, etc.) necessary for receiving data.
   * This function must be non-blocking, so receiving the data must occur on a different
   * thread. Received data can be stored with Spark by calling `store(data)`.
   * 该方法被系统调用时,接收器启动,此功能必须初始化接收数据所需的所有资源(线程、缓冲区等)
   * 这个函数必须是非阻塞的,所以接收的数据必须在一个不同的线程发生,接收的数据可以存储与Spark通过调用的存储(数据)。
   * 如果在开始的线程中有错误,那么可以做以下选项
   * If there are errors in threads started here, then following options can be done
   * (i) `reportError(...)` can be called to report the error to the driver.
   * 可以调用来向驱动程序报告错误
   * The receiving of data will continue uninterrupted.
   * 接收的数据将继续不间断
   * (ii) `stop(...)` can be called to stop receiving data. This will call `onStop()` to
   * clear up all resources allocated (threads, buffers, etc.) during `onStart()`.
   * 可以调用停止接收数据
   * (iii) `restart(...)` can be called to restart the receiver. This will call `onStop()`
   * immediately, and then `onStart()` after a delay.
   * 就将持续不断地接收外界数据,并持续交给 ReceiverSupervisor 进行数据转储
   */
  def onStart()

  /**   
   * This method is called by the system when the receiver is stopped. All resources
   * (threads, buffers, etc.) setup in `onStart()` must be cleaned up in this method.
   * 该方法被系统调用时,接收器被停止,所有在onstart()设置的资源(线程、缓冲区等),必须这个方法设置清理。
   */
  def onStop()

  /** 
   *  Override this to specify a preferred location (hostname). 
   *  重写此指定优先位置(主机名)
   *  */
  def preferredLocation : Option[String] = None

  /**
   * Store a single item of received data to Spark's memory.
   * These single items will be aggregated together into data blocks before
   * being pushed into Spark's memory.
   * 接收单个数据项存储到Spark的内存中,这些单个数据项将聚集在一起到数据块之前被推到Spark内存
   */
  def store(dataItem: T) {
    supervisor.pushSingle(dataItem)
  }

  /** 
   *  Store an ArrayBuffer of received data as a data block into Spark's memory. 
   *  接收到的数据以ArrayBuffer为数据块存储到Spark内存
   *  */
  def store(dataBuffer: ArrayBuffer[T]) {
    supervisor.pushArrayBuffer(dataBuffer, None, None)
  }

  /**
   * Store an ArrayBuffer of received data as a data block into Spark's memory.
   * 接收到的数据以数据块为ArrayBuffer存储在Spark内存
   * The metadata will be associated with this block of data
   * for being used in the corresponding InputDStream.
   * 元数据将相关的数据块使用相应的inputdstream
   */
  def store(dataBuffer: ArrayBuffer[T], metadata: Any) {
    supervisor.pushArrayBuffer(dataBuffer, Some(metadata), None)
  }

  /** 
   *  Store an iterator of received data as a data block into Spark's memory. 
   *  将接收到的迭代器数据作为数据块存储到Spark内存中
   *  */
  def store(dataIterator: Iterator[T]) {
    supervisor.pushIterator(dataIterator, None, None)
  }

  /**
   * Store an iterator of received data as a data block into Spark's memory.
   * 将接收到的迭代器数据作为数据块存储到Spark内存中
   * The metadata will be associated with this block of data
   * 元数据将与此块相关联的数据
   * for being used in the corresponding InputDStream.
   */
  def store(dataIterator: java.util.Iterator[T], metadata: Any) {
    supervisor.pushIterator(dataIterator, Some(metadata), None)
  }

  /** 
   *  Store an iterator of received data as a data block into Spark's memory.
   *  将接收到的迭代器数据作为数据块存储到Spark内存中
   *   */
  def store(dataIterator: java.util.Iterator[T]) {
    supervisor.pushIterator(dataIterator, None, None)
  }

  /**
   * Store an iterator of received data as a data block into Spark's memory.
   * 将接收到的迭代器数据作为数据块存储到Spark内存中
   * The metadata will be associated with this block of data
   * for being used in the corresponding InputDStream.
   */
  def store(dataIterator: Iterator[T], metadata: Any) {
    supervisor.pushIterator(dataIterator, Some(metadata), None)
  }

  /**
   * Store the bytes of received data as a data block into Spark's memory. Note
   * that the data in the ByteBuffer must be serialized using the same serializer
   * that Spark is configured to use.
   * 将接收到的ByteBuffer数据作为数据块存储到Spark内存中
   */
  def store(bytes: ByteBuffer) {
    supervisor.pushBytes(bytes, None, None)
  }

  /**
   * Store the bytes of received data as a data block into Spark's memory.
   * The metadata will be associated with this block of data
   * for being used in the corresponding InputDStream.
   * 将接收到的ByteBuffer数据作为数据块存储到Spark内存中,元数据将与此块相关联的数据
   */
  def store(bytes: ByteBuffer, metadata: Any) {
    supervisor.pushBytes(bytes, Some(metadata), None)
  }

  /** 
   *  Report exceptions in receiving data. 
   *  接收数据中的报告异常
   *  */
  def reportError(message: String, throwable: Throwable) {
    supervisor.reportError(message, throwable)
  }

  /**
   * Restart the receiver. This method schedules the restart and returns
   * immediately. The stopping and subsequent starting of the receiver
   * (by calling `onStop()` and `onStart()`) is performed asynchronously
   * in a background thread. The delay between the stopping and the starting
   * is defined by the Spark configuration `spark.streaming.receiverRestartDelay`.
   * The `message` will be reported to the driver.
   * 重新启动接收器,该方法调度重新启动和立即返回,停止和接收机后续启动(通过调用'onstop()'和'onstart()')
   * 是一个后台线程异步执行,停止和启动,由Spark配置'spark.streaming.receiverRestartDelay'定义之间的延迟
   * 这的“消息”将报告给driver
   */
  def restart(message: String) {
    supervisor.restartReceiver(message)
  }

  /**
   * Restart the receiver. This method schedules the restart and returns
   * immediately. The stopping and subsequent starting of the receiver
   * (by calling `onStop()` and `onStart()`) is performed asynchronously
   * in a background thread. The delay between the stopping and the starting
   * is defined by the Spark configuration `spark.streaming.receiverRestartDelay`.
   * The `message` and `exception` will be reported to the driver.
   * 重新启动接收器,该方法调度重新启动和立即返回,停止和接收机后续启动(通过调用'onstop()'和'onstart()')
   * 是一个后台线程异步执行,停止和启动,由Spark配置'spark.streaming.receiverRestartDelay'定义之间的延迟
   * 这的“消息”将报告给driver
   */
  def restart(message: String, error: Throwable) {
    supervisor.restartReceiver(message, Some(error))
  }

  /**
   * Restart the receiver. This method schedules the restart and returns
   * immediately. The stopping and subsequent starting of the receiver
   * (by calling `onStop()` and `onStart()`) is performed asynchronously
   * in a background thread.
   * *重新启动接收器,该方法调度重新启动和立即返回,
   * 接收器的停止和随后的启动(通过调用'onstop()'和'onstart()')是在后台线程中异步执行
   */
  def restart(message: String, error: Throwable, millisecond: Int) {
    supervisor.restartReceiver(message, Some(error), millisecond)
  }

  /** 
   *  Stop the receiver completely. 
   *  停止接收
   *  */
  def stop(message: String) {
    supervisor.stop(message, None)
  }

  /** 
   *  Stop the receiver completely due to an exception 
   *  由于异常停止接收
   *  */
  def stop(message: String, error: Throwable) {
    supervisor.stop(message, Some(error))
  }

  /** 
   *  Check if the receiver has started or not. 
   *  检查接收器是否已启动或没有启动
   *  */
  def isStarted(): Boolean = {
    supervisor.isReceiverStarted()
  }

  /**
   * Check if receiver has been marked for stopping. Use this to identify when
   * the receiving of data should be stopped.
   * 检查如果接收器已被标记为停止,使用此来识别当接收数据停止.
   */
  def isStopped(): Boolean = {
    supervisor.isReceiverStopped()
  }

  /**
   * Get the unique identifier the receiver input stream that this
   * receiver is associated with.
   * 获取此接收器与之关联的接收器输入流的唯一标识符
   */
  def streamId: Int = id

  /*
   * =================
   * Private methods
   * =================
   */

  /** 
   *  Identifier of the stream this receiver is associated with.
   *  该接收器的流的标识符
   *   */
  private var id: Int = -1

  /** Handler object that runs the receiver. This is instantiated lazily in the worker. */
  @transient private var _supervisor : ReceiverSupervisor = null

  /** 
   *  Set the ID of the DStream that this receiver is associated with.
   *  设置的dstream接收器的ID 
   *  */
  private[streaming] def setReceiverId(id_ : Int) {
    id = id_
  }

  /** 
   *  Attach Network Receiver executor to this receiver. 
   *  将网络接收器执行器连接到该接收器
   *  */
  private[streaming] def attachSupervisor(exec: ReceiverSupervisor) {
    assert(_supervisor == null)
    _supervisor = exec
  }

  /** 
   *  Get the attached supervisor. 
   *  获得附加的监督
   *  */
  private[streaming] def supervisor: ReceiverSupervisor = {
    assert(_supervisor != null,
      "A ReceiverSupervisor have not been attached to the receiver yet. Maybe you are starting " +
        "some computation in the receiver before the Receiver.onStart() has been called.")
    _supervisor
  }
}

