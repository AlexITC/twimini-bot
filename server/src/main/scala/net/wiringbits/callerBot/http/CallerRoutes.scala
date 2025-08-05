package net.wiringbits.callerBot.http

import cats.effect.*
import net.wiringbits.callerBot.twilio.TwilioService
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import upickle.default.*

class CallerRoutes(twilioService: TwilioService) {
  import CallerRoutes.*

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "calls" =>
      for {
        body <- req.as[String].map(read[StartCallRequest](_))

        callId <- twilioService.startCall(
          targetPhoneNumber = body.phoneNumber,
          // normalize to [5, 30]
          ringingTimeoutSeconds = body.ringingTimeoutSeconds
            .getOrElse(15)
            .min(30)
            .max(5),
          // normalize to [15, 120]
          callTimeLimitSeconds = body.callTimeLimitSeconds
            .getOrElse(30)
            .min(120)
            .max(15)
        )

        resp <- Ok(
          s"""{"callId": "$callId"}""",
          `Content-Type`(MediaType.application.json)
        )
      } yield resp
  }
}

object CallerRoutes {
  case class StartCallRequest(
      phoneNumber: String,
      ringingTimeoutSeconds: Option[Int],
      callTimeLimitSeconds: Option[Int]
  )

  implicit val rw: ReadWriter[StartCallRequest] = macroRW
}
