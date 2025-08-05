package net.wiringbits.callerBot

import cats.effect.*
import cats.implicits.*
import com.comcast.ip4s.{host, port}
import net.wiringbits.callerBot.config.{Config, GeminiPromptSettings}
import net.wiringbits.callerBot.gemini.GeminiService
import net.wiringbits.callerBot.http.{CallerRoutes, TwilioRoutes, WebRoutes}
import net.wiringbits.callerBot.twilio.{TwilioService, TwilioWebSocketHandler}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.middleware.ErrorAction
import org.slf4j.LoggerFactory

import java.util.UUID

object Main extends IOApp.Simple {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val config = Config.load()

  case class Resources(callMapRef: Ref[IO, Map[UUID, String]])

  def makeResources: IO[Resources] = {
    for {
      callMapRef <- Ref.of[IO, Map[UUID, String]](Map.empty)
    } yield Resources(callMapRef)
  }

  def runWithResources(res: Resources): IO[Unit] = {
    val twilioRoutes = new TwilioRoutes(
      config,
      res.callMapRef,
      new TwilioWebSocketHandler(new GeminiService(config))
    )
    val callerRoutes = new CallerRoutes(
      new TwilioService(config, res.callMapRef)
    )
    val webRoutes = new WebRoutes()

    EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"8080")
      .withHttpWebSocketApp { wsb =>
        val allRoutes = List(
          callerRoutes.routes,
          twilioRoutes.routes(wsb),
          webRoutes.routes
        )
        val httpApp = Router("/" -> allRoutes.reduce(_ <+> _)).orNotFound

        ErrorAction.log(
          httpApp,
          (t, msg) =>
            IO(logger.error(s"🔥🔥 Unhandled message error: $msg", t)),
          (t, msg) => IO(logger.error(s"🔥🔥 Unhandled service error: $msg", t))
        )
      }
      .build
      .use(_ => IO.never)
  }

  def run: IO[Unit] = {
    logger.info(s"Starting app: ${config.mode}")
    config.mode match {
      case Config.AppMode.Server =>
        makeResources.flatMap(runWithResources)
      case Config.AppMode.LocalTest =>
        // stream mic to gemini to speaker
        val promptSettings = GeminiPromptSettings.randomPrompt("es")
        new MicGeminiSpeaker(new GeminiService(config)).run(promptSettings)
    }
  }
}
