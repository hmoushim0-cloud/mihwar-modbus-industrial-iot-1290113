package sa.factory.mihwarmodbusindust.hardware

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import sa.factory.mihwarmodbusindust.engine.ModbusRtuAcousticProtocolEngine
import sa.factory.mihwarmodbusindust.engine.VibrationSpectralPredictiveEngine
import kotlin.random.Random

data class HardwareTelemetryFrame(
    val peripheralName: String,
    val connectionState: String,
    val livePayload: String,
    val timestamp: Long = System.currentTimeMillis()
)

class HardwareSensorDriver(
    private val modbusEngine: ModbusRtuAcousticProtocolEngine = ModbusRtuAcousticProtocolEngine(),
    private val vibrationEngine: VibrationSpectralPredictiveEngine = VibrationSpectralPredictiveEngine()
) {
    private val peripheral = "3-Axis Vibration Accelerometer + Modbus RS485 Acoustic Probe"

    fun observePeripheralStream(): Flow<HardwareTelemetryFrame> = flow {
        emit(HardwareTelemetryFrame(peripheral, "CONNECTING", "RS-485 Modbus RTU Baud 115200 8N1 Initializing..."))
        delay(250)

        // Generate synthetic initial telemetry frame
        val initialAcoustic = modbusEngine.decodeAcousticTelemetry(
            slaveId = 1,
            registers = listOf(682, 324, 285, 12, 485),
            rawHex = "01 03 0A 02 AA 01 44 01 1D 00 0C 01 E5 4C 82"
        )

        val initialVibration = vibrationEngine.generateReport(
            triaxial = VibrationSpectralPredictiveEngine.TriaxialVibration(1.15, 1.45, 0.82),
            spectrum = VibrationSpectralPredictiveEngine.HarmonicSpectrum(
                fundamentalRpm = 2980.0,
                peak1xAmplitudeMmS = 1.05,
                peak2xAmplitudeMmS = 0.42,
                highFrequencyNoiseG = 0.35,
                bearingBpfoKhz = 0.22
            )
        )

        emit(HardwareTelemetryFrame(
            peripheral,
            "ONLINE",
            "Modbus Slave #01 [OK] | ISO 10816: ${initialVibration.isoZone.gradeName} (${String.format("%.2f", initialVibration.triaxialData.vectorSumVelocityRms)} mm/s) | Acoustic: ${String.format("%.1f", initialAcoustic.decibelRms)} dBA | Ultrasonic: ${String.format("%.1f", initialAcoustic.ultrasonicPeakKhz)} kHz"
        ))

        var frameCounter = 100
        while (true) {
            delay(1500)
            frameCounter++

            // Jittered realistic industrial values
            val vx = 1.1 + (Random.nextDouble(-0.15, 0.25))
            val vy = 1.4 + (Random.nextDouble(-0.2, 0.3))
            val vz = 0.8 + (Random.nextDouble(-0.1, 0.2))

            val triaxial = VibrationSpectralPredictiveEngine.TriaxialVibration(vx, vy, vz)
            val spectrum = VibrationSpectralPredictiveEngine.HarmonicSpectrum(
                fundamentalRpm = 2980.0 + Random.nextInt(-10, 15),
                peak1xAmplitudeMmS = 1.1 + Random.nextDouble(0.0, 0.3),
                peak2xAmplitudeMmS = 0.4 + Random.nextDouble(0.0, 0.2),
                highFrequencyNoiseG = 0.3 + Random.nextDouble(0.0, 0.1),
                bearingBpfoKhz = 0.2 + Random.nextDouble(0.0, 0.1)
            )

            val vibReport = vibrationEngine.generateReport(triaxial, spectrum)
            val dbReading = 68.0 + Random.nextDouble(0.0, 4.0)
            val ultraReading = 32.0 + Random.nextDouble(0.0, 5.0)

            val livePayload = "Modbus [OK] | V_rms: ${String.format("%.2f", vibReport.triaxialData.vectorSumVelocityRms)} mm/s (${vibReport.isoZone.gradeName}) | Acoustic: ${String.format("%.1f", dbReading)} dBA (${String.format("%.1f", ultraReading)} kHz) | Life: ${vibReport.predictedHoursToTrip}h"

            emit(HardwareTelemetryFrame(
                peripheralName = peripheral,
                connectionState = "ACTIVE_STREAMING",
                livePayload = livePayload
            ))
        }
    }
}
