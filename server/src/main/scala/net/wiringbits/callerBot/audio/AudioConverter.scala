package net.wiringbits.callerBot.audio

import cats.effect.IO
import cats.implicits.*

import java.io.{File, FileInputStream, InputStream}
import javax.sound.sampled.{AudioFileFormat, AudioInputStream, AudioSystem}

object AudioConverter {

  def twilioToGemini(
      input: fs2.io.file.Path,
      output: fs2.io.file.Path
  ): IO[Unit] = convert(input, output, twilioToGeminiStream)

  def geminiToTwilio(
      input: fs2.io.file.Path,
      output: fs2.io.file.Path
  ): IO[Unit] = convert(input, output, geminiToTwilioStream)

  // This is where Twilio audio bytes will be written
  def twilioToGeminiStream(twilioStream: InputStream): AudioInputStream = {
    transcodeFormatStream(
      initialStream = twilioStream,
      initialFormat = AudioFormat.Twilio,
      intermediateFormat = AudioFormat.IntermediatePcm8kHz,
      finalFormat = AudioFormat.GeminiInput
    )
  }

  def geminiToTwilioStream(geminiStream: InputStream): AudioInputStream = {
    transcodeFormatStream(
      initialStream = geminiStream,
      initialFormat = AudioFormat.GeminiOutput,
      intermediateFormat = AudioFormat.IntermediatePcm8kHz,
      finalFormat = AudioFormat.Twilio
    )
  }

  def twilioToWav(input: File, output: File): Unit = {
    val _ = scala.util.Using(new FileInputStream(input)) { inputStream =>
      val _ = scala.util.Using(
        new AudioInputStream(
          inputStream,
          AudioFormat.Twilio.underlying,
          input.length()
        )
      ) { audioInputStream =>
        AudioSystem.write(
          audioInputStream,
          AudioFileFormat.Type.WAVE,
          output
        )
      }
    }
  }

  private def transcodeFormatStream(
      initialStream: InputStream,
      initialFormat: AudioFormat,
      intermediateFormat: AudioFormat,
      finalFormat: AudioFormat
  ): AudioInputStream = {
    val initialAudioInputStream = new AudioInputStream(
      initialStream,
      initialFormat.underlying,
      AudioSystem.NOT_SPECIFIED // unknown length
    )

    val intermediateAudioInputStream = AudioSystem.getAudioInputStream(
      intermediateFormat.underlying,
      initialAudioInputStream
    )

    val finalStream = AudioSystem.getAudioInputStream(
      finalFormat.underlying,
      intermediateAudioInputStream
    )
    finalStream
  }

  private def convert(
      input: fs2.io.file.Path,
      output: fs2.io.file.Path,
      process: java.io.InputStream => AudioInputStream
  ): IO[Unit] = {
    val inputStream = java.io.FileInputStream(input.toNioPath.toFile)
    val audioInputStream = process(inputStream)

    for {
      // play with: ffplay -f s16le -ar 24000 -ac 1 output.raw
      _ <- fs2.io
        .readInputStream[IO](
          IO.pure(audioInputStream),
          chunkSize = 512,
          closeAfterUse = true
        )
        .through(fs2.io.file.Files[IO].writeAll(output))
        .compile
        .drain
      _ <- IO.println("Consumer: Finished writing file.")

      // Manual cleanup for this unsafe test setup (in a real app, use Resource.make)
      // This is a best effort cleanup after unsafeRunSync, not guaranteed on error.
      _ <- IO {
        inputStream.close()
        audioInputStream.close()
      }
    } yield ()
  }

}
