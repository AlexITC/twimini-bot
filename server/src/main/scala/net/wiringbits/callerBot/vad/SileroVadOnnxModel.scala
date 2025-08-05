package net.wiringbits.callerBot.vad

import ai.onnxruntime.{OnnxTensor, OrtEnvironment, OrtException, OrtSession}

import scala.jdk.CollectionConverters.*
import scala.util.Using

@throws[OrtException]
object SileroVadOnnxModel {
  def apply(modelPath: String): SileroVadOnnxModel = new SileroVadOnnxModel(
    modelPath
  )

  // Define a list of supported sample rates
  val sampleRates: List[Int] = List(8000, 16000)

}

@throws[OrtException]
class SileroVadOnnxModel(modelPath: String) {
  import SileroVadOnnxModel.*

  val session: OrtSession = {
    // Get the ONNX runtime environment
    val env = OrtEnvironment.getEnvironment()
    // Create an ONNX session options object
    val opts = new OrtSession.SessionOptions()
    // Set the InterOp thread count to 1, InterOp threads are used for parallel processing of different computation graph operations
    opts.setInterOpNumThreads(1)
    // Set the IntraOp thread count to 1, IntraOp threads are used for parallel processing within a single operation
    opts.setIntraOpNumThreads(1)
    // Add a CPU device, setting to false disables CPU execution optimization
    opts.addCPU(true)
    // Create an ONNX session using the environment, model path, and options
    env.createSession(modelPath, opts)
  }

  private var state: Array[Array[Array[Float]]] = Array.ofDim[Float](2, 1, 128)
  private var context: Array[Array[Float]] = Array.empty
  private var lastSr = 0
  private var lastBatchSize = 0

  resetStates()

  def resetStates(): Unit = {
    state = Array.ofDim[Float](2, 1, 128)
    context = Array.empty
    lastSr = 0
    lastBatchSize = 0
  }

  case class ValidationResult(x: Array[Array[Float]], sr: Int)

  @throws[OrtException]
  def close(): Unit = session.close()

  def validateInput(
      xInput: Array[Array[Float]],
      srInput: Int
  ): ValidationResult = {
    var x = xInput

    if (x.length == 1) {
      x = Array(x(0))
    }

    if (x.length > 2) {
      throw new IllegalArgumentException(
        s"Incorrect audio data dimension: ${x(0).length}"
      );
    }

    val validationResult =
      if (srInput != 16000 && (srInput % 16000 == 0)) {
        val step: Int = srInput / 16000
        val reducedX = x.map { row =>
          val reduced = row.indices
            .by(step)
            .map(i => row(i))
            .toArray
          reduced
        }
        ValidationResult(x = reducedX, sr = 16000)
      } else {
        ValidationResult(x = x, sr = srInput)
      }

    if (!sampleRates.contains(srInput)) {
      throw new IllegalArgumentException(
        s"Only supports sample rates ${sampleRates} (or multiples of 16000)"
      )
    } else if (srInput.toFloat / x(0).length > 31.25) {
      throw new IllegalArgumentException("Input audio is too short")
    } else validationResult
  }

  private def concatenate(
      a: Array[Array[Float]],
      b: Array[Array[Float]]
  ): Array[Array[Float]] = {
    if (a.length != b.length) {
      throw new IllegalArgumentException(
        "The number of rows in both arrays must be the same."
      );
    }
    a.zip(b).map { case (rowA, rowB) => rowA ++ rowB }
  }

  private def getLastColumns(
      array: Array[Array[Float]],
      contextSize: Int
  ): Array[Array[Float]] = {
    val cols = array(0).length

    if (contextSize > cols) {
      throw new IllegalArgumentException(
        "contextSize cannot be greater than the number of columns in the array."
      );
    }

    array.map { row =>
      row.slice(cols - contextSize, cols)
    }
  }

  /** Executes the Silero VAD model with the given audio input and sample rate.
    *
    * @param x
    *   A 2D array representing the audio input. Each sub-array is a channel or
    *   batch of audio samples. Expected shape is [batch_size][samples], where
    *   the number of samples must match the expected size for the sample rate
    *   (e.g., 512 for 16000 Hz, 256 for 8000 Hz).
    * @param sr
    *   The sample rate of the audio input. Supported values: 8000 or 16000 Hz
    *   (or multiples of 16000).
    * @return
    *   An array of floats representing the voice activity detection
    *   probabilities (one per batch).
    * @throws OrtException
    *   If there is an error during ONNX inference or tensor creation.
    * @throws IllegalArgumentException
    *   If the input shape, sample rate, or sample count is invalid.
    */
  @throws[OrtException]
  def call(x: Array[Array[Float]], sr: Int): Array[Float] = {
    val validateResult = validateInput(xInput = x, srInput = sr)

    val numberSamples =
      if (validateResult.sr == 16000)
        512
      else
        256

    if (x(0).length != numberSamples) {
      throw new IllegalArgumentException(
        s"Provided number of samples is ${x(0).length} (Supported values: 256 for 8000 sample rate, 512 for 16000)"
      )
    }

    val batchSize = x.length
    val contextSize: Int =
      if (sr == 16000)
        64
      else
        32

    if (
      (lastBatchSize == 0) || (lastSr != 0 && lastSr != sr) || (lastBatchSize != batchSize)
    ) {
      resetStates()
    }

    if (context.length == 0) {
      context = Array.ofDim[Float](batchSize, contextSize)
    }

    val newX = concatenate(context, validateResult.x)

    val env = OrtEnvironment.getEnvironment()

    Using.Manager { use =>
      val inputTensor = use(OnnxTensor.createTensor(env, newX))
      val stateTensor = use(OnnxTensor.createTensor(env, state))
      val srTensor = use(OnnxTensor.createTensor(env, Array[Long](sr)))

      val inputs = Map(
        "input" -> inputTensor,
        "sr" -> srTensor,
        "state" -> stateTensor
      ).asJava

      // Call the ONNX model for calculation
      val ortOutputs = use(session.run(inputs))

      val output: Array[Array[Float]] =
        ortOutputs.get(0).getValue.asInstanceOf[Array[Array[Float]]]
      state =
        ortOutputs.get(1).getValue.asInstanceOf[Array[Array[Array[Float]]]]
      context = getLastColumns(x, contextSize)
      lastSr = validateResult.sr
      lastBatchSize = batchSize

      output(0)
    }.get

  }

}
