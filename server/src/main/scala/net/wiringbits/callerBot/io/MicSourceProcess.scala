package net.wiringbits.callerBot.io

import cats.effect.IO
import cats.effect.kernel.Resource
import net.wiringbits.callerBot.audio.AudioFormat

import java.io.IOException
import javax.sound.sampled.{
  AudioSystem,
  DataLine,
  LineUnavailableException,
  TargetDataLine
}

object MicSourceProcess {
  def stream(audioFormat: AudioFormat): fs2.Stream[IO, Array[Byte]] = {
    val micLineResource: Resource[IO, TargetDataLine] =
      Resource.make(
        IO {
          val micDataLineInfo =
            new DataLine.Info(classOf[TargetDataLine], audioFormat.underlying)
          if (!AudioSystem.isLineSupported(micDataLineInfo)) {
            throw new RuntimeException(
              "Microphone line not supported with the specified format."
            )
          }
          AudioSystem.getLine(micDataLineInfo).asInstanceOf[TargetDataLine]
        }.flatTap { line =>
          IO(line.open(audioFormat.underlying)) >>
            IO(line.start()) >>
            IO.println("Microphone recording started...")
        }
      )(line =>
        val run = IO(line.stop()) >> IO(line.close()) >>
          IO.println("Microphone recording stopped and line closed.")
        run.handleErrorWith { e =>
          IO.println(s"Error closing microphone line: ${e.getMessage}")
        }
      )

    // Use the resource to create the stream of bytes
    fs2.Stream.resource(micLineResource).flatMap { targetDataLine =>
      // Adjust buffer size as needed, with my tests this is ~100ms
      val bufferSize = targetDataLine.getBufferSize / 5
      val buffer = new Array[Byte](bufferSize)

      fs2.Stream
        .repeatEval {
          IO.blocking {
            val bytesRead = targetDataLine.read(buffer, 0, buffer.length)

            if (bytesRead > 0) {
              buffer.take(bytesRead)
            } else {
              Array.empty[Byte]
            }
          }.onError { // Handle specific exceptions that `read` might throw
            case e: LineUnavailableException =>
              IO.raiseError(
                new RuntimeException(
                  s"Microphone line unavailable during read: ${e.getMessage}",
                  e
                )
              )
            case e: IOException =>
              IO.raiseError(
                new RuntimeException(
                  s"IO error during microphone read: ${e.getMessage}",
                  e
                )
              )
            case e: Throwable =>
              IO.raiseError(
                new RuntimeException(
                  s"Unexpected error during microphone read: ${e.getMessage}",
                  e
                )
              )
          }
        }
        .takeWhile(_.nonEmpty)
    }
  }
}
