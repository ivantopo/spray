/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.can.client

import scala.collection.immutable
import scala.collection.immutable.Queue
import scala.concurrent.duration.Duration
import akka.actor._
import akka.io.Inet
import spray.can.client.HttpHostConnector._
import spray.can.Http
import spray.io.ClientSSLEngineProvider
import spray.http._
import spray.util.SimpleStash

private class HttpHostConnectionSlot(host: String, port: Int,
                                     sslEncryption: Boolean,
                                     options: immutable.Traversable[Inet.SocketOption],
                                     idleTimeout: Duration,
                                     clientConnectionSettingsGroup: ActorRef)(implicit sslEngineProvider: ClientSSLEngineProvider)
    extends Actor with SimpleStash with ActorLogging {

  // we cannot sensibly recover from crashes
  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def receive: Receive = unconnected

  def unconnected: Receive = {
    context.setReceiveTimeout(idleTimeout)

    {
      case ctx: RequestContext ⇒
        log.debug("Attempting new connection to {}:{}", host, port)
        clientConnectionSettingsGroup ! Http.Connect(host, port, sslEncryption, None, options, None)
        context.setReceiveTimeout(Duration.Undefined)
        context.become(connecting(Queue(ctx)))

      case _: Http.CloseCommand ⇒ context.stop(self)

      case ReceiveTimeout ⇒
        log.debug("Initiating idle shutdown")
        context.parent ! DemandIdleShutdown
        context.become { // after having initiated our shutdown we must bounce all requests
          case ctx: RequestContext  ⇒ context.parent ! ctx
          case _: Http.CloseCommand ⇒ context.stop(self)
        }
    }
  }

  def connecting(openRequests: Queue[RequestContext], aborted: Option[Http.CloseCommand] = None): Receive = {
    case _: Http.Connected if aborted.isDefined ⇒
      sender ! aborted.get
      openRequests foreach clear("Connection actively closed", retry = false)
      context.become(terminating(context.watch(sender)))

    case _: Http.Connected ⇒
      log.debug("Connection to {}:{} established, dispatching {} pending requests", host, port, openRequests.size)
      openRequests foreach dispatchToServer(sender)
      context.become(connected(context.watch(sender), openRequests))

    case ctx: RequestContext    ⇒ context.become(connecting(openRequests.enqueue(ctx)))

    case cmd: Http.CloseCommand ⇒ context.become(connecting(openRequests, aborted = Some(cmd)))

    case _: Http.CommandFailed ⇒
      log.debug("Connection attempt failed")
      val error = new Http.ConnectionAttemptFailedException(host, port)
      openRequests foreach clear(error, retry = false)
      if (aborted.isEmpty) {
        context.parent ! Disconnected(openRequests.size)
        context.become(unconnected)
      } else context.stop(self)
  }

  def connected(httpConnection: ActorRef, openRequests: Queue[RequestContext],
                closeAfterResponseEnd: Boolean = false): Receive = {
    case part: HttpResponsePart if openRequests.nonEmpty ⇒
      dispatchToCommander(openRequests.head, part)

      def handleResponseCompletion(closeAfterResponseEnd: Boolean): Unit = {
        context.parent ! RequestCompleted
        context.become {
          if (closeAfterResponseEnd)
            closing(httpConnection, openRequests.tail,
              "Connection was closed by the peer through `Connection: close`", retry = true)
          else connected(httpConnection, openRequests.tail)
        }
      }
      part match {
        case x: HttpResponse ⇒ handleResponseCompletion(x.connectionCloseExpected)
        case ChunkedResponseStart(x: HttpResponse) ⇒
          context.become(connected(httpConnection, openRequests, x.connectionCloseExpected))
        case _: MessageChunk      ⇒ // nothing to do
        case _: ChunkedMessageEnd ⇒ handleResponseCompletion(closeAfterResponseEnd)
      }

    case x: HttpResponsePart ⇒
      log.warning("Received unexpected response for non-existing request: {}, dropping", x)

    case ctx: RequestContext ⇒
      dispatchToServer(httpConnection)(ctx)
      context.become(connected(httpConnection, openRequests.enqueue(ctx), closeAfterResponseEnd))

    case ev @ Http.SendFailed(part) ⇒
      log.debug("Sending {} failed, closing connection", format(part))
      httpConnection ! Http.Close
      context.become(closing(httpConnection, openRequests, "Error sending request (part)", retry = true))

    case ev: Http.CommandFailed ⇒
      log.debug("Received {}, closing connection", ev)
      httpConnection ! Http.Close
      context.become(closing(httpConnection, openRequests, "Command error", retry = true))

    case ev @ Timedout(part) ⇒
      log.debug("{} timed out, closing connection", format(part))
      context.become(closing(httpConnection, openRequests, new Http.RequestTimeoutException(part, format(part) + " timed out"), retry = true))

    case cmd: Http.CloseCommand ⇒
      httpConnection ! cmd
      openRequests foreach clear(s"Connection actively closed ($cmd)", retry = false)
      context.become(terminating(httpConnection))

    case ev: Http.ConnectionClosed ⇒

      val errorMsgForOpenRequests = ev match {
        case Http.PeerClosed ⇒ "Premature connection close (the server doesn't appear to support request pipelining)"
        case x               ⇒ x.toString
      }
      reportDisconnection(openRequests, errorMsgForOpenRequests, retry = true)
      context.become(waitingForConnectionTermination(httpConnection))

    case Terminated(`httpConnection`) ⇒
      reportDisconnection(openRequests, "Unexpected connection termination", retry = true)
      context.become(unconnected)
  }

  def closing(httpConnection: ActorRef, openRequests: Queue[RequestContext], error: String, retry: Boolean): Receive =
    closing(httpConnection, openRequests, new Http.ConnectionException(error), retry)

  def closing(httpConnection: ActorRef, openRequests: Queue[RequestContext], error: Http.ConnectionException,
              retry: Boolean): Receive = {
    case _: Http.ConnectionClosed ⇒
      reportDisconnection(openRequests, error, retry)
      context.become(waitingForConnectionTermination(httpConnection))

    case Terminated(`httpConnection`) ⇒
      reportDisconnection(openRequests, error, retry)
      unstashAll()
      context.become(unconnected)

    case x ⇒ stash(x)
  }
  def waitingForConnectionTermination(httpConnection: ActorRef): Receive = {
    case Terminated(`httpConnection`) ⇒
      unstashAll()
      context.become(unconnected)
    case x ⇒ stash(x)
  }

  def terminating(httpConnection: ActorRef): Receive = {
    case _: Http.ConnectionClosed     ⇒ // ignore
    case Terminated(`httpConnection`) ⇒ context.stop(self)
  }
  def reportDisconnection(openRequests: Queue[RequestContext], error: String, retry: Boolean): Unit =
    reportDisconnection(openRequests, new Http.ConnectionException(error), retry)
  def reportDisconnection(openRequests: Queue[RequestContext], error: Http.ConnectionException, retry: Boolean): Unit = {
    context.parent ! Disconnected(openRequests.size)
    openRequests foreach clear(error, retry)
  }

  def clear(error: String, retry: Boolean): RequestContext ⇒ Unit = clear(new Http.ConnectionException(error), retry)
  def clear(error: Http.ConnectionException, retry: Boolean): RequestContext ⇒ Unit = {
    case ctx @ RequestContext(request, retriesLeft, _) if retry && request.canBeRetried && retriesLeft > 0 ⇒
      log.warning("{} in response to {} with {} retries left, retrying...", error.getMessage, format(request), retriesLeft)
      context.parent ! ctx.copy(retriesLeft = retriesLeft - 1)

    case ctx: RequestContext ⇒
      log.warning("{} in response to {} with no retries left, dispatching error...", error.getMessage, format(ctx.request))

      dispatchToCommander(ctx, Status.Failure(error))
  }

  def dispatchToServer(httpConnection: ActorRef)(ctx: RequestContext): Unit = {
    if (log.isDebugEnabled) log.debug("Dispatching {} across connection {}", format(ctx.request), httpConnection)
    httpConnection ! ctx.request
  }

  def dispatchToCommander(requestContext: RequestContext, message: Any) = {
    val RequestContext(request, _, commander) = requestContext
    if (log.isDebugEnabled) log.debug("Delivering {} for {}", formatResponse(message), format(request))
    commander ! message
  }

  def format(part: HttpMessagePart) = part match {
    case x: HttpRequestPart with HttpMessageStart ⇒
      val request = x.message.asInstanceOf[HttpRequest]
      s"${request.method} request to ${request.uri}"
    case MessageChunk(body, _) ⇒ body.length.toString + " byte request chunk"
    case x                     ⇒ x.toString
  }

  def formatResponse(part: Any) = part match {
    case HttpResponse(status, _, _, _) ⇒ status.value.toString + " response"
    case ChunkedResponseStart(HttpResponse(status, _, _, _)) ⇒ status.value.toString + " response start"
    case MessageChunk(body, _) ⇒ body.length.toString + " byte response chunk"
    case Status.Failure(_) ⇒ "Status.Failure"
    case x ⇒ x.toString
  }
}
