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

package org.apache.spark.ui

import java.util.{Timer, TimerTask}

import org.apache.spark._

/**
 * ConsoleProgressBar shows the progress of stages in the next line of the console. It poll the
 * status of active stages from `sc.statusTracker` periodically, the progress bar will be showed
 * up after the stage has ran at least 500ms. If multiple stages run in the same time, the status
 * of them will be combined together, showed in one line.
  * ConsoleProgressBar显示控制台下一行的阶段进度,它定期从“sc.statusTracker”轮询活动阶段的状态,
  * 进度条将在stages运行至少500ms后显示,如果多个阶段在同一时间运行,他们的状态将被组合在一起,显示在一行。
 */
private[spark] class ConsoleProgressBar(sc: SparkContext) extends Logging {
  // Carrige return
  val CR = '\r'
  // Update period of progress bar, in milliseconds
  //进度条的更新周期,以毫秒为单位
  val UPDATE_PERIOD = 200L
  // Delay to show up a progress bar, in milliseconds
  //延迟显示一个进度条,以毫秒为单位
  val FIRST_DELAY = 500L

  // The width of terminal 终端的宽度
  // //System.getenv()和System.getProperties()的区别
  //System.getenv() 返回系统环境变量值 设置系统环境变量：当前登录用户主目录下的".bashrc"文件中可以设置系统环境变量
  //System.getProperties() 返回Java进程变量值 通过命令行参数的"-D"选项
  val TerminalWidth = if (!sys.env.getOrElse("COLUMNS", "").isEmpty) {
    sys.env.get("COLUMNS").get.toInt
  } else {
    80
  }

  var lastFinishTime = 0L
  var lastUpdateTime = 0L
  var lastProgressBar = ""

  // Schedule a refresh thread to run periodically
  //创建一个新计时器,其相关的线程具有指定的名称,并且可以指定作为守护程序运行
  private val timer = new Timer("refresh progress", true)
  //FIRST_DELAY延迟执行的毫秒数,即在delay毫秒之后第一次执行,重复执行的时间间隔
  timer.schedule(new TimerTask{
    override def run() {
      refresh()
    }
  }, FIRST_DELAY, UPDATE_PERIOD)

  /**
   * Try to refresh the progress bar in every cycle
    * 尝试在每个周期刷新进度条
   */
  private def refresh(): Unit = synchronized {
    val now = System.currentTimeMillis()
    //println(now+"==="+lastFinishTime+"==="+FIRST_DELAY)
    if (now - lastFinishTime < FIRST_DELAY) {
      return
    }
    val stageIds = sc.statusTracker.getActiveStageIds()
    //println("==stageIds=="+stageIds.mkString(","));
    //flatten可以把嵌套的结构展开.
    //List(List(1,2),List(3,4)).flatten
    //res0: List[Int] = List(1, 2, 3, 4)
    val stages = stageIds.map(sc.statusTracker.getStageInfo).flatten.filter(_.numTasks() > 1)
      .filter(now - _.submissionTime() > FIRST_DELAY).sortBy(_.stageId())
   // println("==stages=="+stageIds.mkString(",")+"=="+stages.length);
    if (stages.length > 0) {
      //同时显示最多3个阶段
      show(now, stages.take(3))  // display at most 3 stages in same time
    }
  }

  /**
   * Show progress bar in console. The progress bar is displayed in the next line
   * after your last output, keeps overwriting itself to hold in one line. The logging will follow
   * the progress bar, then progress bar will be showed in next line without overwrite logs.
    * 在控制台中显示进度条,进度条显示在上一次输出后的下一行中,不断覆盖自己以保持一行,
    * 日志记录将跟随进度条,则进度条将显示在下一行而不覆盖日志。
   */
  private def show(now: Long, stages: Seq[SparkStageInfo]) {

    val width = TerminalWidth / stages.size
   // println(TerminalWidth+"==="+stages.size+"==="+width)
    val bar = stages.map { s =>
      val total = s.numTasks()
      //header字符串的长度9 ==[Stage 0:>
      val header = s"[Stage ${s.stageId()}:"
      //tailer字符串的长度12,==(0 + 0) / 2]
      val tailer = s"(${s.numCompletedTasks()} + ${s.numActiveTasks()}) / $total]"
      val w = width - header.length - tailer.length
      //println(width+"==="+header.length+"==="+tailer.length+"==="+w)
      val bar = if (w > 0) {
        //进度百分数=w*完成任务数/总数
        val percent = w * s.numCompletedTasks() / total


        (0 until w).map { i =>
         val a= if (i < percent) "=" else if (i == percent) ">" else " "
         if (i < percent) "=" else if (i == percent) ">" else " "
        }.mkString("")
      } else {
        ""
      }
      header + bar + tailer
    }.mkString("")

   // println("===="+bar+"===")
    // only refresh if it's changed of after 1 minute (or the ssh connection will be closed
    // after idle some time)
    //只有在1分钟后更改（或者ssh连接将在空闲一段时间后关闭）才刷新
    if (bar != lastProgressBar || now - lastUpdateTime > 60 * 1000L) {
      System.err.print(CR + bar)
      lastUpdateTime = now
    }
    lastProgressBar = bar
  }

  /**
   * Clear the progress bar if showed.
    * 如果显示,清除进度条。
   */
  private def clear() {
    if (!lastProgressBar.isEmpty) {
      System.err.printf(CR + " " * TerminalWidth + CR)
      lastProgressBar = ""
    }
  }

  /**
   * Mark all the stages as finished, clear the progress bar if showed, then the progress will not
   * interweave with output of jobs.
    * 将所有阶段标记为已完成,清除进度条,如果显示,则进度不会与作业的输出交织
   */
  def finishAll(): Unit = synchronized {
    clear()
    lastFinishTime = System.currentTimeMillis()
  }

  /**
   * Tear down the timer thread.  The timer thread is a GC root, and it retains the entire
   * SparkContext if it's not terminated.
    * 拆除定时器线程,定时器线程是一个GC根,它保留了整个sparkcontext如果不是终止。
   */
  def stop(): Unit = timer.cancel()
}
