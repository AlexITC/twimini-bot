package net.wiringbits.callerBot.twilio

import cats.effect.{IO, Ref}
import com.twilio.Twilio
import com.twilio.`type`.PhoneNumber
import com.twilio.rest.api.v2010.account.Call
import net.wiringbits.callerBot.config.Config

import java.util.UUID

class TwilioService(config: Config, callMapRef: Ref[IO, Map[UUID, String]]) {

  def startCall(
      targetPhoneNumber: String,
      ringingTimeoutSeconds: Int,
      callTimeLimitSeconds: Int
  ): IO[UUID] = {
    // Make the call with our webhook URL
    val callId = UUID.randomUUID()

    for {
      // Initialize Twilio
      _ <- IO.println("Initializing twilio...")
      _ <- IO(Twilio.init(config.twilio.accountSid, config.twilio.authToken))

      _ <- IO.println(s"📞 Starting call to $targetPhoneNumber...")
      _ <- IO.println(s"🌐 Using webhook URL: ${config.twilioWebhookUrl}")
      _ <- IO.println(s"Starting call: $callId")

      callUrl = config.twilioWebSocketUrl(callId)
      twiml = new com.twilio.`type`.Twiml(s"""
        |<Response>
        |  <Connect>
        |    <Stream url="$callUrl"/>
        |  </Connect>
        |</Response>
        |""".stripMargin)

      call <- IO.blocking {
        Call
          .creator(
            new PhoneNumber(targetPhoneNumber), // To
            new PhoneNumber(config.twilio.phoneNumber), // From
            twiml
          )
          .setTimeout(ringingTimeoutSeconds)
          .setTimeLimit(callTimeLimitSeconds)
          .setStatusCallback(new java.net.URI(config.twilioWebhookUrl))
          .setStatusCallbackEvent(
            java.util.Arrays
              .asList("initiated", "ringing", "answered", "completed")
          )
          .create()
      }

      _ <- callMapRef.update(map => map + (callId -> call.getSid))

      _ <- IO.println(s"New call started: $callId")
      _ <- IO.println(s"📋 Call SID: ${call.getSid}")
      _ <- IO.println(s"📞 Status: ${call.getStatus}")
      _ <- IO.println(s"✅ Call initiated successfully!")
    } yield callId
  }
}
