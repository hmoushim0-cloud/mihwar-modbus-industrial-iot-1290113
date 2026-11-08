package sa.factory.mihwarmodbusindust

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import sa.factory.mihwarmodbusindust.data.repository.MihwarModbusRepositoryImpl
import sa.factory.mihwarmodbusindust.engine.ModbusRtuAcousticProtocolEngine
import sa.factory.mihwarmodbusindust.engine.VibrationSpectralPredictiveEngine
import sa.factory.mihwarmodbusindust.hardware.HardwareSensorDriver
import sa.factory.mihwarmodbusindust.model.MihwarModbusItem

class MihwarModbusRegressionTest {

    private val modbusEngine = ModbusRtuAcousticProtocolEngine()
    private val vibrationEngine = VibrationSpectralPredictiveEngine()

    @Test
    fun test01_modbusCrc16ComputationAndVerification() {
        // Standard Modbus RTU request: Slave 1, Func 3, Start 0, Count 10 -> [01 03 00 00 00 0A]
        val payload = byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x0A)
        val crc = modbusEngine.computeCrc16(payload)
        
        // Modbus CRC-16 for this frame is 0xCDC5 (in little endian: low=0xC5, high=0xCD)
        val lowByte = crc and 0xFF
        val highByte = (crc shr 8) and 0xFF
        assertEquals(0xC5, lowByte)
        assertEquals(0xCD, highByte)
    }

    @Test
    fun test02_modbusReadHoldingRegistersRequestSerialization() {
        val request = modbusEngine.buildReadHoldingRegistersRequest(
            slaveId = 1,
            startAddress = 0x0100,
            quantity = 5
        )

        assertEquals(8, request.size)
        assertEquals(1, request[0].toInt())
        assertEquals(0x03, request[1].toInt())
        assertEquals(0x01, request[2].toInt())
        assertEquals(0x00, request[3].toInt())
        assertEquals(0x00, request[4].toInt())
        assertEquals(0x05, request[5].toInt())

        // Verify trailing CRC matches computeCrc16
        val calculatedCrc = modbusEngine.computeCrc16(request, 6)
        val crcLow = request[6].toInt() and 0xFF
        val crcHigh = request[7].toInt() and 0xFF
        assertEquals(calculatedCrc, crcLow or (crcHigh shl 8))
    }

    @Test
    fun test03_modbusReadResponseSuccessParsing() {
        // Mock response: Slave 1, Func 3, 10 bytes (5 registers: [680, 320, 250, 15, 450])
        val registersData = byteArrayOf(
            0x02, 0xA8.toByte(), // 680
            0x01, 0x40.toByte(), // 320
            0x00, 0xFA.toByte(), // 250
            0x00, 0x0F.toByte(), // 15
            0x01, 0xC2.toByte()  // 450
        )
        val packetWithoutCrc = byteArrayOf(0x01, 0x03, 10) + registersData
        val crc = modbusEngine.computeCrc16(packetWithoutCrc)
        val fullPacket = packetWithoutCrc + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())

        val response = modbusEngine.parseResponse(fullPacket, expectedSlaveId = 1)
        assertTrue(response is ModbusRtuAcousticProtocolEngine.ModbusResponse.Success)

        val success = response as ModbusRtuAcousticProtocolEngine.ModbusResponse.Success
        assertEquals(1, success.slaveId)
        assertEquals(0x03, success.functionCode)
        assertEquals(5, success.registers.size)
        assertEquals(680, success.registers[0])
        assertEquals(320, success.registers[1])
        assertEquals(250, success.registers[2])
        assertEquals(15, success.registers[3])
        assertEquals(450, success.registers[4])
    }

    @Test
    fun test04_modbusExceptionResponseHandling() {
        // Exception frame: Slave 1, Func 0x83 (0x03 + 0x80), Exception 0x02 (ILLEGAL_DATA_ADDRESS)
        val exPacketWithoutCrc = byteArrayOf(0x01, 0x83.toByte(), 0x02)
        val crc = modbusEngine.computeCrc16(exPacketWithoutCrc)
        val exPacket = exPacketWithoutCrc + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())

        val response = modbusEngine.parseResponse(exPacket, expectedSlaveId = 1)
        assertTrue(response is ModbusRtuAcousticProtocolEngine.ModbusResponse.ExceptionResponse)

        val ex = response as ModbusRtuAcousticProtocolEngine.ModbusResponse.ExceptionResponse
        assertEquals(1, ex.slaveId)
        assertEquals(0x02, ex.exceptionCode)
        assertEquals("ILLEGAL_DATA_ADDRESS", ex.errorName)
    }

    @Test
    fun test05_modbusCrcErrorDetection() {
        val packet = byteArrayOf(0x01, 0x03, 0x02, 0x01, 0x00, 0x11, 0x22) // corrupted CRC
        val response = modbusEngine.parseResponse(packet)
        assertTrue(response is ModbusRtuAcousticProtocolEngine.ModbusResponse.CrcError)
    }

    @Test
    fun test06_acousticProbeTelemetryDecoding() {
        // Registers: 745 (74.5 dB), 442 (44.2 kHz), 480 (4.80 crest), 38 spikes, 512 (51.2 C)
        val telemetry = modbusEngine.decodeAcousticTelemetry(
            slaveId = 1,
            registers = listOf(745, 442, 480, 38, 512),
            rawHex = "01 03 0A ..."
        )

        assertEquals(74.5, telemetry.decibelRms, 0.01)
        assertEquals(44.2, telemetry.ultrasonicPeakKhz, 0.01)
        assertEquals(4.80, telemetry.crestFactor, 0.01)
        assertEquals(38, telemetry.emissionSpikeCount)
        assertEquals(51.2, telemetry.temperatureCelsius, 0.01)
        assertTrue(telemetry.acousticFrictionAlert) // ultrasonic > 42 kHz triggers friction alert
    }

    @Test
    fun test07_iso10816VibrationZoneClassification() {
        assertEquals(
            VibrationSpectralPredictiveEngine.Iso10816Zone.ZONE_A,
            vibrationEngine.classifyIso10816Zone(1.4)
        )
        assertEquals(
            VibrationSpectralPredictiveEngine.Iso10816Zone.ZONE_B,
            vibrationEngine.classifyIso10816Zone(3.2)
        )
        assertEquals(
            VibrationSpectralPredictiveEngine.Iso10816Zone.ZONE_C,
            vibrationEngine.classifyIso10816Zone(7.8)
        )
        assertEquals(
            VibrationSpectralPredictiveEngine.Iso10816Zone.ZONE_D,
            vibrationEngine.classifyIso10816Zone(14.5)
        )
    }

    @Test
    fun test08_mechanicalDefectDiagnosticIdentification() {
        // Test 1: Unbalance (dominant 1X)
        val unbalanceSpectrum = VibrationSpectralPredictiveEngine.HarmonicSpectrum(
            fundamentalRpm = 1490.0,
            peak1xAmplitudeMmS = 4.8,
            peak2xAmplitudeMmS = 0.5,
            highFrequencyNoiseG = 0.4,
            bearingBpfoKhz = 0.1
        )
        val unbalanceDiag = vibrationEngine.diagnoseDefect(unbalanceSpectrum, 5.0)
        assertEquals(VibrationSpectralPredictiveEngine.MechanicalDefectType.ROTOR_UNBALANCE_1X, unbalanceDiag)

        // Test 2: Misalignment (dominant 2X)
        val misalignSpectrum = VibrationSpectralPredictiveEngine.HarmonicSpectrum(
            fundamentalRpm = 2980.0,
            peak1xAmplitudeMmS = 1.2,
            peak2xAmplitudeMmS = 3.6,
            highFrequencyNoiseG = 0.5,
            bearingBpfoKhz = 0.2
        )
        val misalignDiag = vibrationEngine.diagnoseDefect(misalignSpectrum, 4.2)
        assertEquals(VibrationSpectralPredictiveEngine.MechanicalDefectType.SHAFT_MISALIGNMENT_2X, misalignDiag)

        // Test 3: Bearing Race Spalling (high frequency noise)
        val bearingSpectrum = VibrationSpectralPredictiveEngine.HarmonicSpectrum(
            fundamentalRpm = 2980.0,
            peak1xAmplitudeMmS = 0.8,
            peak2xAmplitudeMmS = 0.6,
            highFrequencyNoiseG = 3.2,
            bearingBpfoKhz = 2.4
        )
        val bearingDiag = vibrationEngine.diagnoseDefect(bearingSpectrum, 3.8)
        assertEquals(VibrationSpectralPredictiveEngine.MechanicalDefectType.BEARING_RACE_DEFECT_BPFO, bearingDiag)
    }

    @Test
    fun test09_hardwareSensorDriverEmitsValidTelemetry() = runBlocking {
        val driver = HardwareSensorDriver(modbusEngine, vibrationEngine)
        val initialFrame = driver.observePeripheralStream().first()

        assertEquals("CONNECTING", initialFrame.connectionState)
        assertTrue(initialFrame.peripheralName.contains("Modbus RS485"))
        assertTrue(initialFrame.livePayload.contains("RS-485 Modbus RTU"))
    }

    @Test
    fun test10_repositoryCartAndTaxCalculation() = runBlocking {
        val sensorDriver = HardwareSensorDriver(modbusEngine, vibrationEngine)
        val repository = MihwarModbusRepositoryImpl(sensorDriver)

        val item1 = MihwarModbusItem("iot-1", "فحص الترددات الطيفية بالموجات الصوتية", "فحص دقيق", 180.0, "معتمد", "الخدمات الرئيسية")
        val item2 = MihwarModbusItem("iot-2", "معايرة اهتزاز التوربينات والمضخات", "مطابقة ISO 10816", 320.0, "فوري", "المراقبة الحية")

        repository.addToCart(item1)
        repository.addToCart(item2)

        val order = repository.checkout("Mada Contactless EMV L2")

        assertEquals(500.0, order.subtotalSar, 0.01)
        assertEquals(75.0, order.vatSar, 0.01) // 15% VAT
        assertEquals(575.0, order.totalSar, 0.01)
        assertEquals("Mada Contactless EMV L2", order.paymentMethod)
        assertEquals(0, repository.activeCart.value.size)
    }
}
