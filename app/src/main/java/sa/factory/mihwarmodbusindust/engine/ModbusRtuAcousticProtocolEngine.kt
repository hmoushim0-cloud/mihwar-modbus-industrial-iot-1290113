package sa.factory.mihwarmodbusindust.engine

/**
 * Modbus RTU RS-485 industrial communication protocol engine.
 * Implements binary frame construction, CRC-16 calculation, and acoustic probe telemetry decoding.
 */
class ModbusRtuAcousticProtocolEngine {

    companion object {
        const val FUNCTION_READ_HOLDING_REGISTERS = 0x03
        const val FUNCTION_READ_INPUT_REGISTERS = 0x04
        const val FUNCTION_WRITE_SINGLE_REGISTER = 0x06
        const val FUNCTION_WRITE_MULTIPLE_REGISTERS = 0x10

        // Modbus Exception Codes
        const val EXCEPTION_ILLEGAL_FUNCTION = 0x01
        const val EXCEPTION_ILLEGAL_DATA_ADDRESS = 0x02
        const val EXCEPTION_ILLEGAL_DATA_VALUE = 0x03
        const val EXCEPTION_SLAVE_DEVICE_FAILURE = 0x04

        // RS-485 Modbus CRC-16 Polynomial (0xA001 reversed)
        private const val CRC_POLYNOMIAL = 0xA001
        private const val CRC_INITIAL_VALUE = 0xFFFF
    }

    data class AcousticProbeTelemetry(
        val slaveId: Int,
        val decibelRms: Double,
        val ultrasonicPeakKhz: Double,
        val crestFactor: Double,
        val emissionSpikeCount: Int,
        val temperatureCelsius: Double,
        val acousticFrictionAlert: Boolean,
        val rawHexFrame: String
    )

    sealed interface ModbusResponse {
        data class Success(val slaveId: Int, val functionCode: Int, val registers: List<Int>) : ModbusResponse
        data class ExceptionResponse(val slaveId: Int, val exceptionCode: Int, val errorName: String) : ModbusResponse
        data class CrcError(val calculatedCrc: Int, val receivedCrc: Int) : ModbusResponse
        data class InvalidFrame(val reason: String) : ModbusResponse
    }

    /**
     * Computes the 16-bit Modbus CRC for a given byte buffer.
     * Returns an integer containing the low and high byte (Little-Endian).
     */
    fun computeCrc16(bytes: ByteArray, length: Int = bytes.size): Int {
        var crc = CRC_INITIAL_VALUE
        for (i in 0 until length) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                if ((crc and 0x0001) != 0) {
                    crc = (crc ushr 1) xor CRC_POLYNOMIAL
                } else {
                    crc = crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Builds a Modbus RTU Read Holding Registers (0x03) binary request.
     */
    fun buildReadHoldingRegistersRequest(slaveId: Int, startAddress: Int, quantity: Int): ByteArray {
        val frame = ByteArray(8)
        frame[0] = (slaveId and 0xFF).toByte()
        frame[1] = FUNCTION_READ_HOLDING_REGISTERS.toByte()
        frame[2] = ((startAddress shr 8) and 0xFF).toByte()
        frame[3] = (startAddress and 0xFF).toByte()
        frame[4] = ((quantity shr 8) and 0xFF).toByte()
        frame[5] = (quantity and 0xFF).toByte()

        val crc = computeCrc16(frame, 6)
        frame[6] = (crc and 0xFF).toByte()          // CRC Low Byte
        frame[7] = ((crc shr 8) and 0xFF).toByte()   // CRC High Byte
        return frame
    }

    /**
     * Parses a Modbus RTU response frame, verifying CRC and handling exception frames.
     */
    fun parseResponse(rawBytes: ByteArray, expectedSlaveId: Int = 1): ModbusResponse {
        if (rawBytes.size < 4) {
            return ModbusResponse.InvalidFrame("Frame length ${rawBytes.size} is less than minimum 4 bytes")
        }

        val slaveId = rawBytes[0].toInt() and 0xFF
        val functionCode = rawBytes[1].toInt() and 0xFF

        // Validate CRC
        val payloadLength = rawBytes.size - 2
        val calculatedCrc = computeCrc16(rawBytes, payloadLength)
        val receivedCrcLow = rawBytes[rawBytes.size - 2].toInt() and 0xFF
        val receivedCrcHigh = rawBytes[rawBytes.size - 1].toInt() and 0xFF
        val receivedCrc = receivedCrcLow or (receivedCrcHigh shl 8)

        if (calculatedCrc != receivedCrc) {
            return ModbusResponse.CrcError(calculatedCrc, receivedCrc)
        }

        // Check for Exception Response (Function code MSB set: code + 0x80)
        if ((functionCode and 0x80) != 0) {
            val exceptionCode = rawBytes[2].toInt() and 0xFF
            val errorName = when (exceptionCode) {
                EXCEPTION_ILLEGAL_FUNCTION -> "ILLEGAL_FUNCTION"
                EXCEPTION_ILLEGAL_DATA_ADDRESS -> "ILLEGAL_DATA_ADDRESS"
                EXCEPTION_ILLEGAL_DATA_VALUE -> "ILLEGAL_DATA_VALUE"
                EXCEPTION_SLAVE_DEVICE_FAILURE -> "SLAVE_DEVICE_FAILURE"
                else -> "UNKNOWN_EXCEPTION_CODE_$exceptionCode"
            }
            return ModbusResponse.ExceptionResponse(slaveId, exceptionCode, errorName)
        }

        // Standard Read Response: [SlaveID, Func, ByteCount, Data..., CRCLow, CRCHigh]
        val byteCount = rawBytes[2].toInt() and 0xFF
        if (rawBytes.size != 3 + byteCount + 2) {
            return ModbusResponse.InvalidFrame("ByteCount $byteCount does not match packet size ${rawBytes.size}")
        }

        val registers = mutableListOf<Int>()
        var offset = 3
        while (offset < 3 + byteCount) {
            val high = rawBytes[offset].toInt() and 0xFF
            val low = rawBytes[offset + 1].toInt() and 0xFF
            registers.add((high shl 8) or low)
            offset += 2
        }

        return ModbusResponse.Success(slaveId, functionCode, registers)
    }

    /**
     * Decodes the 5 registers of the RS-485 Acoustic Probe:
     * Reg 0: Decibel RMS (scaled x10, e.g. 745 = 74.5 dBA)
     * Reg 1: Ultrasonic Peak Frequency (scaled x10, e.g. 384 = 38.4 kHz)
     * Reg 2: Acoustic Crest Factor (scaled x100, e.g. 342 = 3.42)
     * Reg 3: Transient Emission Spikes Count
     * Reg 4: Probe Temperature (signed Celsius, scaled x10, e.g. 482 = 48.2 C)
     */
    fun decodeAcousticTelemetry(slaveId: Int, registers: List<Int>, rawHex: String = ""): AcousticProbeTelemetry {
        require(registers.size >= 5) { "Acoustic probe telemetry requires at least 5 registers, got ${registers.size}" }

        val dbRms = registers[0] / 10.0
        val ultrasonicKhz = registers[1] / 10.0
        val crestFactor = registers[2] / 100.0
        val spikeCount = registers[3]
        val tempRaw = registers[4].toShort() // preserve sign
        val tempCelsius = tempRaw / 10.0

        // Critical friction indicator: high frequency ultrasonic emissions (>42 kHz) combined with crest factor > 4.5
        val frictionAlert = ultrasonicKhz > 42.0 || crestFactor > 4.5 || dbRms > 95.0

        return AcousticProbeTelemetry(
            slaveId = slaveId,
            decibelRms = dbRms,
            ultrasonicPeakKhz = ultrasonicKhz,
            crestFactor = crestFactor,
            emissionSpikeCount = spikeCount,
            temperatureCelsius = tempCelsius,
            acousticFrictionAlert = frictionAlert,
            rawHexFrame = rawHex
        )
    }

    /**
     * Converts a byte array into an uppercase hex string format for industrial diagnostics.
     */
    fun toHexString(bytes: ByteArray): String {
        return bytes.joinToString(separator = " ") { "%02X".format(it) }
    }
}
