package net.wiringbits.callerBot.config

import java.util.UUID

case class Config(
    mode: Config.AppMode,
    twilio: Config.TwilioConfig,
    geminiApiKey: String,
    baseUrl: String
) {
  def webSocketBaseUrl: String = baseUrl
    .replace("http://", "ws://")
    .replace("https://", "wss://")

  val twilioWebhookUrl: String = s"$baseUrl/api/twilio/webhook"

  def twilioWebSocketUrl(callId: UUID): String =
    s"$webSocketBaseUrl/api/twilio/ws/$callId"
}

object Config {
  enum AppMode:
    case Server, LocalTest

  case class TwilioConfig(
      accountSid: String,
      authToken: String,
      phoneNumber: String
  )

  def load(): Config = {
    val mode = sys.env.get("MODE").map(_.toUpperCase) match {
      case Some("SERVER") => AppMode.Server
      case _              => AppMode.LocalTest
    }

    val twilioAccountSid = getEnvVar("TWILIO_ACCOUNT_SID")
    val twilioAuthToken = getEnvVar("TWILIO_AUTH_TOKEN")
    val twilioPhoneNumber = getEnvVar("TWILIO_PHONE_NUMBER")

    val geminiApiKey = getEnvVar("GEMINI_API_KEY")
    val baseUrl = getEnvVar("BASE_URL")

    Config(
      mode = mode,
      twilio = TwilioConfig(
        accountSid = twilioAccountSid,
        authToken = twilioAuthToken,
        phoneNumber = twilioPhoneNumber
      ),
      geminiApiKey = geminiApiKey,
      baseUrl = baseUrl
    )
  }

  private def getEnvVar(
      name: String,
      default: Option[String] = None
  ): String = {
    sys.env.getOrElse(
      name,
      default.getOrElse(
        throw new RuntimeException(s"$name environment variable required")
      )
    )
  }
}
