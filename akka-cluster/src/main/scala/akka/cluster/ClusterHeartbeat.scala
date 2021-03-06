/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.{ ActorLogging, ActorRef, ActorSelection, Address, Actor, RootActorPath, Props }
import akka.cluster.ClusterEvent._
import akka.routing.MurmurHash
import akka.remote.FailureDetectorRegistry
import akka.remote.PriorityMessage
import akka.actor.DeadLetterSuppression

/**
 * INTERNAL API.
 *
 * Receives Heartbeat messages and replies.
 */
private[cluster] final class ClusterHeartbeatReceiver extends Actor with ActorLogging {
  import ClusterHeartbeatSender._

  val selfHeartbeatRsp = HeartbeatRsp(Cluster(context.system).selfUniqueAddress)

  def receive = {
    case Heartbeat(from) ⇒ sender() ! selfHeartbeatRsp
  }

}

/**
 * INTERNAL API
 */
private[cluster] object ClusterHeartbeatSender {
  /**
   * Sent at regular intervals for failure detection.
   */
  final case class Heartbeat(from: Address) extends ClusterMessage with PriorityMessage with DeadLetterSuppression

  /**
   * Sent as reply to [[Heartbeat]] messages.
   */
  final case class HeartbeatRsp(from: UniqueAddress) extends ClusterMessage with PriorityMessage with DeadLetterSuppression

  // sent to self only
  case object HeartbeatTick
  final case class ExpectedFirstHeartbeat(from: UniqueAddress)

}

/*
 * INTERNAL API
 *
 * This actor is responsible for sending the heartbeat messages to
 * a few other nodes, which will reply and then this actor updates the
 * failure detector.
 */
private[cluster] final class ClusterHeartbeatSender extends Actor with ActorLogging {
  import ClusterHeartbeatSender._

  val cluster = Cluster(context.system)
  import cluster.{ selfAddress, selfUniqueAddress, scheduler }
  import cluster.settings._
  import cluster.InfoLogger._
  import context.dispatcher

  // the failureDetector is only updated by this actor, but read from other places
  val failureDetector = Cluster(context.system).failureDetector

  val selfHeartbeat = Heartbeat(selfAddress)

  var state = ClusterHeartbeatSenderState(
    ring = HeartbeatNodeRing(selfUniqueAddress, Set(selfUniqueAddress), Set.empty, MonitoredByNrOfMembers),
    oldReceiversNowUnreachable = Set.empty[UniqueAddress],
    failureDetector)

  // start periodic heartbeat to other nodes in cluster
  val heartbeatTask = scheduler.schedule(PeriodicTasksInitialDelay max HeartbeatInterval,
    HeartbeatInterval, self, HeartbeatTick)

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent])
  }

  override def postStop(): Unit = {
    state.activeReceivers.foreach(a ⇒ failureDetector.remove(a.address))
    heartbeatTask.cancel()
    cluster.unsubscribe(self)
  }

  /**
   * Looks up and returns the remote cluster heartbeat connection for the specific address.
   */
  def heartbeatReceiver(address: Address): ActorSelection =
    context.actorSelection(RootActorPath(address) / "system" / "cluster" / "heartbeatReceiver")

  def receive = initializing

  def initializing: Actor.Receive = {
    case s: CurrentClusterState ⇒
      init(s)
      context.become(active)
    case HeartbeatTick ⇒
  }

  def active: Actor.Receive = {
    case HeartbeatTick                ⇒ heartbeat()
    case HeartbeatRsp(from)           ⇒ heartbeatRsp(from)
    case MemberUp(m)                  ⇒ addMember(m)
    case MemberRemoved(m, _)          ⇒ removeMember(m)
    case UnreachableMember(m)         ⇒ unreachableMember(m)
    case ReachableMember(m)           ⇒ reachableMember(m)
    case _: MemberEvent               ⇒ // not interested in other types of MemberEvent
    case ExpectedFirstHeartbeat(from) ⇒ triggerFirstHeartbeat(from)
  }

  def init(snapshot: CurrentClusterState): Unit = {
    val nodes: Set[UniqueAddress] = snapshot.members.collect {
      case m if m.status == MemberStatus.Up ⇒ m.uniqueAddress
    }(collection.breakOut)
    val unreachable: Set[UniqueAddress] = snapshot.unreachable.map(_.uniqueAddress)
    state = state.init(nodes, unreachable)
  }

  def addMember(m: Member): Unit =
    if (m.uniqueAddress != selfUniqueAddress)
      state = state.addMember(m.uniqueAddress)

  def removeMember(m: Member): Unit =
    if (m.uniqueAddress == cluster.selfUniqueAddress) {
      // This cluster node will be shutdown, but stop this actor immediately
      // to avoid further updates
      context stop self
    } else {
      state = state.removeMember(m.uniqueAddress)
    }

  def unreachableMember(m: Member): Unit =
    state = state.unreachableMember(m.uniqueAddress)

  def reachableMember(m: Member): Unit =
    state = state.reachableMember(m.uniqueAddress)

  def heartbeat(): Unit = {
    state.activeReceivers foreach { to ⇒
      if (cluster.failureDetector.isMonitoring(to.address))
        log.debug("Cluster Node [{}] - Heartbeat to [{}]", selfAddress, to.address)
      else {
        log.debug("Cluster Node [{}] - First Heartbeat to [{}]", selfAddress, to.address)
        // schedule the expected first heartbeat for later, which will give the
        // other side a chance to reply, and also trigger some resends if needed
        scheduler.scheduleOnce(HeartbeatExpectedResponseAfter, self, ExpectedFirstHeartbeat(to))
      }
      heartbeatReceiver(to.address) ! selfHeartbeat
    }

  }

  def heartbeatRsp(from: UniqueAddress): Unit = {
    log.debug("Cluster Node [{}] - Heartbeat response from [{}]", selfAddress, from.address)
    state = state.heartbeatRsp(from)
  }

  def triggerFirstHeartbeat(from: UniqueAddress): Unit =
    if (state.activeReceivers(from) && !failureDetector.isMonitoring(from.address)) {
      log.debug("Cluster Node [{}] - Trigger extra expected heartbeat from [{}]", selfAddress, from.address)
      failureDetector.heartbeat(from.address)
    }

}

/**
 * INTERNAL API
 * State of [[ClusterHeartbeatSender]]. Encapsulated to facilitate unit testing.
 * It is immutable, but it updates the failureDetector.
 */
private[cluster] final case class ClusterHeartbeatSenderState(
  ring: HeartbeatNodeRing,
  oldReceiversNowUnreachable: Set[UniqueAddress],
  failureDetector: FailureDetectorRegistry[Address]) {

  val activeReceivers: Set[UniqueAddress] = ring.myReceivers ++ oldReceiversNowUnreachable

  def selfAddress = ring.selfAddress

  def init(nodes: Set[UniqueAddress], unreachable: Set[UniqueAddress]): ClusterHeartbeatSenderState =
    copy(ring = ring.copy(nodes = nodes + selfAddress, unreachable = unreachable))

  def addMember(node: UniqueAddress): ClusterHeartbeatSenderState =
    membershipChange(ring :+ node)

  def removeMember(node: UniqueAddress): ClusterHeartbeatSenderState = {
    val newState = membershipChange(ring :- node)

    failureDetector remove node.address
    if (newState.oldReceiversNowUnreachable(node))
      newState.copy(oldReceiversNowUnreachable = newState.oldReceiversNowUnreachable - node)
    else
      newState
  }

  def unreachableMember(node: UniqueAddress): ClusterHeartbeatSenderState =
    membershipChange(ring.copy(unreachable = ring.unreachable + node))

  def reachableMember(node: UniqueAddress): ClusterHeartbeatSenderState =
    membershipChange(ring.copy(unreachable = ring.unreachable - node))

  private def membershipChange(newRing: HeartbeatNodeRing): ClusterHeartbeatSenderState = {
    val oldReceivers = ring.myReceivers
    val removedReceivers = oldReceivers -- newRing.myReceivers
    var adjustedOldReceiversNowUnreachable = oldReceiversNowUnreachable
    removedReceivers foreach { a ⇒
      if (failureDetector.isAvailable(a.address))
        failureDetector remove a.address
      else
        adjustedOldReceiversNowUnreachable += a
    }
    copy(newRing, adjustedOldReceiversNowUnreachable)
  }

  def heartbeatRsp(from: UniqueAddress): ClusterHeartbeatSenderState =
    if (activeReceivers(from)) {
      failureDetector heartbeat from.address
      if (oldReceiversNowUnreachable(from)) {
        // back from unreachable, ok to stop heartbeating to it
        if (!ring.myReceivers(from))
          failureDetector remove from.address
        copy(oldReceiversNowUnreachable = oldReceiversNowUnreachable - from)
      } else this
    } else this

}

/**
 * INTERNAL API
 *
 * Data structure for picking heartbeat receivers. The node ring is
 * shuffled by deterministic hashing to avoid picking physically co-located
 * neighbors.
 *
 * It is immutable, i.e. the methods return new instances.
 */
private[cluster] final case class HeartbeatNodeRing(
  selfAddress: UniqueAddress,
  nodes: Set[UniqueAddress],
  unreachable: Set[UniqueAddress],
  monitoredByNrOfMembers: Int) {

  require(nodes contains selfAddress, s"nodes [${nodes.mkString(", ")}] must contain selfAddress [${selfAddress}]")

  private val nodeRing: immutable.SortedSet[UniqueAddress] = {
    implicit val ringOrdering: Ordering[UniqueAddress] = Ordering.fromLessThan[UniqueAddress] { (a, b) ⇒
      val ha = a.##
      val hb = b.##
      ha < hb || (ha == hb && Member.addressOrdering.compare(a.address, b.address) < 0)
    }

    immutable.SortedSet() ++ nodes
  }

  /**
   * Receivers for `selfAddress`. Cached for subsequent access.
   */
  lazy val myReceivers: immutable.Set[UniqueAddress] = receivers(selfAddress)

  private val useAllAsReceivers = monitoredByNrOfMembers >= (nodeRing.size - 1)

  /**
   * The receivers to use from a specified sender.
   */
  def receivers(sender: UniqueAddress): Set[UniqueAddress] =
    if (useAllAsReceivers)
      nodeRing - sender
    else {

      // Pick nodes from the iterator until n nodes that are not unreachable have been selected.
      // Intermediate unreachable nodes up to `monitoredByNrOfMembers` are also included in the result.
      // The reason for not limiting it to strictly monitoredByNrOfMembers is that the leader must
      // be able to continue its duties (e.g. removal of downed nodes) when many nodes are shutdown
      // at the same time and nobody in the remaining cluster is monitoring some of the shutdown nodes.
      // This was reported in issue #16624.
      @tailrec def take(n: Int, iter: Iterator[UniqueAddress], acc: Set[UniqueAddress]): (Int, Set[UniqueAddress]) =
        if (iter.isEmpty || n == 0) (n, acc)
        else {
          val next = iter.next()
          val isUnreachable = unreachable(next)
          if (isUnreachable && acc.size >= monitoredByNrOfMembers)
            take(n, iter, acc) // skip the unreachable, since we have already picked `monitoredByNrOfMembers`
          else if (isUnreachable)
            take(n, iter, acc + next) // include the unreachable, but don't count it
          else
            take(n - 1, iter, acc + next) // include the reachable
        }

      val (remaining, slice1) = take(monitoredByNrOfMembers, nodeRing.from(sender).tail.iterator, Set.empty)
      val slice =
        if (remaining == 0)
          slice1
        else {
          // wrap around
          val (_, slice2) = take(remaining, nodeRing.to(sender).iterator.filterNot(_ == sender), slice1)
          slice2
        }

      slice
    }

  /**
   * Add a node to the ring.
   */
  def :+(node: UniqueAddress): HeartbeatNodeRing = if (nodes contains node) this else copy(nodes = nodes + node)

  /**
   * Remove a node from the ring.
   */
  def :-(node: UniqueAddress): HeartbeatNodeRing =
    if (nodes.contains(node) || unreachable.contains(node))
      copy(nodes = nodes - node, unreachable = unreachable - node)
    else this

}
