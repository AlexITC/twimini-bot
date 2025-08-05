package net.wiringbits.callerBot.http

import cats.effect.*
import com.twilio.security.RequestValidator
import net.wiringbits.callerBot.config.{Config, GeminiPromptSettings}
import net.wiringbits.callerBot.twilio.TwilioWebSocketHandler
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.{`Accept-Language`, `Content-Type`}
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci.CIString

import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Handles Twilio webhooks and WebSockets using http4s.
  */
class TwilioRoutes(
    config: Config,
    callMap: Ref[IO, Map[UUID, String]],
    twilioWebSocketHandler: TwilioWebSocketHandler
) {

  private val twilioRequestValidator = new RequestValidator(
    config.twilio.authToken
  )

  // Custom extractor for UUIDs in the path
  private object UUIDVar {
    def unapply(str: String): Option[UUID] = Try(UUID.fromString(str)).toOption
  }

  // Define the http4s routes
  def routes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case req @ POST -> Root / "api" / "twilio" / "webhook" =>
        handleWebhook(req)
      case req @ GET -> Root / "api" / "twilio" / "ws" / UUIDVar(callId) =>
        // the call can be answered a single time
        callMap.getAndUpdate(_ - callId).map(_.get(callId)).flatMap {
          case None => NotFound(s"Call ID $callId not active or invalid.")
          case Some(twilioCallSid) =>
            val lang = req.headers
              .get[`Accept-Language`]
              .map(
                _.values.head.primaryTag.toLowerCase().startsWith("es")
              ) match
              case Some(true) => "es"
              case _          => "en"
            val promptSettings = GeminiPromptSettings.randomPrompt(lang)

            wsb.build(
              twilioWebSocketHandler
                .audioPipeline(callId, twilioCallSid, promptSettings)
            )
        }
    }
  }

  /** Handles Twilio's status callback webhook. Validates the request signature
    * and returns a TwiML response.
    */
  private def handleWebhook(req: Request[IO]): IO[Response[IO]] = {
    for {
      // http4s provides a decoder for URL-encoded forms
      form <- req.as[UrlForm]
      formParams = form.values.map { case (k, v) =>
        k -> v.headOption.getOrElse("")
      }

      // Get the signature header in a case-insensitive way
      signatureOpt = req.headers
        .get(CIString("X-Twilio-Signature"))
        .map(_.head.value)

      // Construct the full URL that Twilio requested
      eventUrl = config.twilioWebhookUrl + req.uri.query.renderString

      // Validate the signature
      isValid: Boolean = signatureOpt.exists { sig =>
        twilioRequestValidator.validate(eventUrl, formParams.asJava, sig)
      }

      response <-
        if (isValid) {
          // Your logic from `handleValidatedWebhook` goes here
          val callStatus = formParams.getOrElse("CallStatus", "")
          println(s"Twilio webhook received, status: $callStatus")
          // Always return an empty TwiML response to Twilio
          Ok("<Response></Response>", `Content-Type`(MediaType.text.xml))
        } else {
          IO.pure(
            Response[IO](status = Status.Unauthorized)
              .withEntity(s"Invalid Twilio signature for URL")
          )
        }
    } yield response
  }

}
