package mesosphere.marathon.upgrade

import akka.actor.{ Props, Actor, ActorLogging }
import akka.event.{ LoggingReceive, EventStream }
import mesosphere.marathon.TaskUpgradeCanceledException
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.TaskTracker
import org.apache.mesos.Protos.TaskID
import org.apache.mesos.{ Protos, SchedulerDriver }

import scala.collection.mutable
import scala.concurrent.Promise

object TaskReplaceActor {
  def props(driver: SchedulerDriver,
            taskQueue: LaunchQueue,
            taskTracker: TaskTracker,
            eventBus: EventStream,
            app: AppDefinition,
            promise: Promise[Unit]): Props = {
    Props(new TaskReplaceActor(driver, taskQueue, taskTracker, eventBus, app, promise))
  }

  private case class KillTask(taskID: String)
}

class TaskReplaceActor(
    driver: SchedulerDriver,
    taskQueue: LaunchQueue,
    taskTracker: TaskTracker,
    eventBus: EventStream,
    app: AppDefinition,
    promise: Promise[Unit]) extends Actor with ActorLogging {

  val tasksToKill = taskTracker.get(app.id).filter(_.getId != app.id.toString)
  val appId = app.id
  val version = app.version.toString
  var healthy = Set.empty[String]
  var newTasksStarted: Int = 0
  var oldTaskIds = tasksToKill.map(_.getId)
  val toKill = oldTaskIds.to[mutable.Queue]
  var totalOldTasksToKill = toKill.size
  var killed = Set.empty[String]
  var maxCapacity = (app.instances * (1 + app.upgradeStrategy.maximumOverCapacity)).toInt

  override def preStart(): Unit = {
    eventBus.subscribe(self, classOf[MesosStatusUpdateEvent])
    eventBus.subscribe(self, classOf[HealthStatusChanged])

    val minHealthy = (app.instances * app.upgradeStrategy.minimumHealthCapacity).ceil.toInt
    val nrToKillImmediately = math.max(0, toKill.size - minHealthy)

    // make sure at least one task can be started to get the ball rolling
    if (nrToKillImmediately == 0 && maxCapacity == app.instances)
      maxCapacity += 1

    log.info(s"For minimumHealthCapacity ${app.upgradeStrategy.minimumHealthCapacity} of ${app.id.toString} leave " +
      s"$minHealthy tasks running, maximum capacity $maxCapacity, killing $nrToKillImmediately tasks immediately")

    for (_ <- 0 until nrToKillImmediately) {
      killOldTask(toKill.dequeue())
    }

    conciliateNewTasks()

    log.info("Resetting the backoff delay before restarting the app")
    taskQueue.resetDelay(app)
  }

  override def postStop(): Unit = {
    eventBus.unsubscribe(self)
    if (!promise.isCompleted)
      promise.tryFailure(
        new TaskUpgradeCanceledException(
          "The task upgrade has been cancelled"))
  }

  override def receive: Receive = LoggingReceive {
    Seq[Receive](
      receiveTaskKill,
      receiveStatusChanges
    ).reduceLeft(_.orElse[Any, Unit](_))
  }

  private[this] def receiveStatusChanges: Receive = {
    val behavior =
      if (app.healthChecks.nonEmpty)
        healthCheckingBehavior
      else
        taskStateBehavior

    behavior orElse commonBehavior
  }

  private[this] def receiveTaskKill: Receive = {
    case TaskReplaceActor.KillTask(taskID) =>
      log.info(s"Killing $taskID now")
      killOldTask(taskID)
      checkFinished()
  }

  def taskStateBehavior: PartialFunction[Any, Unit] = {
    case MesosStatusUpdateEvent(slaveId, taskId, "TASK_RUNNING", _, `appId`, _, _, `version`, _, _) =>
      handleNewHealthyTask(taskId)
  }

  def healthCheckingBehavior: PartialFunction[Any, Unit] = {
    case HealthStatusChanged(`appId`, taskId, `version`, true, _, _) if !healthy(taskId) =>
      handleNewHealthyTask(taskId)
  }

  def commonBehavior: PartialFunction[Any, Unit] = {
    case MesosStatusUpdateEvent(slaveId, taskId, ReplaceErrorState(_), _, `appId`, _, _, `version`, _, _) if !oldTaskIds(taskId) => // scalastyle:ignore line.size.limit
      log.error(s"New task $taskId failed on slave $slaveId during app ${app.id.toString} restart")
      healthy -= taskId
      taskQueue.add(app)

    case MesosStatusUpdateEvent(slaveId, taskId, ReplaceErrorState(_), _, `appId`, _, _, _, _, _) if oldTaskIds(taskId) => // scalastyle:ignore line.size.limit
      log.error(s"Old task $taskId failed on slave $slaveId during app ${app.id.toString} restart")
      oldTaskIds -= taskId
      conciliateNewTasks()

    case x: Any => log.debug(s"Received $x")
  }

  def conciliateNewTasks(): Unit = {
    val leftCapacity = math.max(0, maxCapacity - oldTaskIds.size - newTasksStarted)
    val tasksNotStartedYet = math.max(0, app.instances - newTasksStarted)
    val tasksToStartNow = math.min(tasksNotStartedYet, leftCapacity)
    if (tasksToStartNow > 0) {
      log.info(s"Reconciliating tasks during app ${app.id.toString} restart: queuing $tasksToStartNow new tasks")
      taskQueue.add(app, tasksToStartNow)
      newTasksStarted += tasksToStartNow
    }
  }

  def handleNewHealthyTask(taskId: String): Unit = {
    healthy += taskId
    if (toKill.nonEmpty) {
      val killing = toKill.dequeue()
      log.info(s"Scheduling old task $killing to be killed in ${app.upgradeStrategy.killOldTasksDelay}" +
        s" because $taskId became reachable")
      import context.dispatcher
      context.system.scheduler.scheduleOnce(app.upgradeStrategy.killOldTasksDelay, self,
        TaskReplaceActor.KillTask(killing))
    }
    checkFinished()
  }

  def killOldTask(oldTaskId: String): Unit = {
    killed += oldTaskId
    driver.killTask(buildTaskId(oldTaskId))
  }

  def checkFinished(): Unit = {
    if (healthy.size == app.instances && killed.size == totalOldTasksToKill) {
      log.info(s"All tasks for $appId are healthy and all old tasks have been killed.")
      promise.success(())
      context.stop(self)
    }
  }

  def buildTaskId(id: String): TaskID =
    TaskID.newBuilder()
      .setValue(id)
      .build()
}

private object ReplaceErrorState {
  def unapply(state: String): Option[String] = state match {
    case "TASK_ERROR" | "TASK_FAILED" | "TASK_KILLED" | "TASK_LOST" => Some(state)
    case _ => None
  }
}

