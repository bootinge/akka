package akka.streams.impl.ops

import akka.streams.impl._
import akka.streams.Operation.{ Source, FromProducerSource }
import rx.async.spi.{ Subscription, Subscriber }
import rx.async.api.Producer

class FromProducerSourceImpl[O](downstream: Downstream[O], ctx: ContextEffects, producer: Producer[O]) extends DynamicSyncSource {
  def initial = WaitingForRequest

  def WaitingForRequest: State =
    new State {
      def handleRequestMore(n: Int): Effect = startSubscribing(n)
      def handleCancel(): Effect = ???
    }

  def startSubscribing(requested: Int): Effect = {
    val nextState = new WaitingForSubscription(requested)
    become(nextState)
    BasicEffects.Subscribe(producer.getPublisher, nextState)
  }

  class WaitingForSubscription(var originallyRequested: Int) extends State with Subscriber[O] {
    def onSubscribe(subscription: Subscription): Unit = ctx.runInContext {
      val upstream = BasicEffects.forSubscription(subscription)
      become(Subscribed(upstream))
      if (originallyRequested > 0) subscription.requestMore(originallyRequested)
      Continue
    }
    def onNext(element: O): Unit = ctx.runInContext(downstream.next(element))
    def onComplete(): Unit = ctx.runInContext(downstream.complete)
    def onError(cause: Throwable): Unit = ctx.runInContext(downstream.error(cause))

    def handleRequestMore(n: Int): Effect = {
      originallyRequested += n
      Continue
    }
    def handleCancel(): Effect = ???
  }
  def Subscribed(upstream: Upstream) = new State {
    def handleRequestMore(n: Int): Effect = upstream.requestMore(n)
    def handleCancel(): Effect = upstream.cancel
  }
}