package sa.factory.mihwarmodbusindust.engine

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 3-Axis Vibration Accelerometer and Spectral Predictive Maintenance Engine.
 * Implements ISO 10816 / ISO 20816 vibration severity standards and FFT harmonic defect diagnostics.
 */
class VibrationSpectralPredictiveEngine {

    enum class Iso10816Zone(val gradeName: String, val severityLevel: String) {
        ZONE_A("Zone A", "OPTIMAL_NEW_CONDITION"),
        ZONE_B("Zone B", "ACCEPTABLE_UNRESTRICTED"),
        ZONE_C("Zone C", "UNSATISFACTORY_MAINTENANCE_REQUIRED"),
        ZONE_D("Zone D", "CRITICAL_UNACCEPTABLE_IMMINENT_TRIP")
    }

    enum class MechanicalDefectType {
        NORMAL,
        ROTOR_UNBALANCE_1X,
        SHAFT_MISALIGNMENT_2X,
        BEARING_RACE_DEFECT_BPFO,
        STRUCTURAL_LOOSENESS
    }

    data class TriaxialVibration(
        val axisXVelocityRmsMmS: Double, // Axial
        val axisYVelocityRmsMmS: Double, // Horizontal Radial
        val axisZVelocityRmsMmS: Double  // Vertical Radial
    ) {
        val vectorSumVelocityRms: Double
            get() = sqrt(axisXVelocityRmsMmS * axisXVelocityRmsMmS +
                         axisYVelocityRmsMmS * axisYVelocityRmsMmS +
                         axisZVelocityRmsMmS * axisZVelocityRmsMmS)
    }

    data class HarmonicSpectrum(
        val fundamentalRpm: Double,
        val peak1xAmplitudeMmS: Double,
        val peak2xAmplitudeMmS: Double,
        val highFrequencyNoiseG: Double,
        val bearingBpfoKhz: Double
    )

    data class VibrationDiagnosticReport(
        val triaxialData: TriaxialVibration,
        val isoZone: Iso10816Zone,
        val primaryDefect: MechanicalDefectType,
        val predictedHoursToTrip: Int,
        val kurtosisFactor: Double,
        val recommendationSummary: String
    )

    /**
     * Evaluates total vibration velocity against ISO 10816-3 Class II / Class III standard limits.
     * Zone A: < 1.8 mm/s
     * Zone B: 1.8 - 4.5 mm/s
     * Zone C: 4.5 - 11.2 mm/s
     * Zone D: >= 11.2 mm/s
     */
    fun classifyIso10816Zone(velocityRmsMmS: Double): Iso10816Zone {
        return when {
            velocityRmsMmS < 1.8 -> Iso10816Zone.ZONE_A
            velocityRmsMmS < 4.5 -> Iso10816Zone.ZONE_B
            velocityRmsMmS < 11.2 -> Iso10816Zone.ZONE_C
            else -> Iso10816Zone.ZONE_D
        }
    }

    /**
     * Identifies the dominant mechanical defect from harmonic spectral peaks.
     */
    fun diagnoseDefect(spectrum: HarmonicSpectrum, vectorVelocityMmS: Double): MechanicalDefectType {
        if (vectorVelocityMmS < 2.0 && spectrum.highFrequencyNoiseG < 0.8) {
            return MechanicalDefectType.NORMAL
        }

        val totalHarmonic = max(0.001, spectrum.peak1xAmplitudeMmS + spectrum.peak2xAmplitudeMmS)
        val ratio1x = spectrum.peak1xAmplitudeMmS / totalHarmonic
        val ratio2x = spectrum.peak2xAmplitudeMmS / totalHarmonic

        return when {
            spectrum.highFrequencyNoiseG > 2.5 || spectrum.bearingBpfoKhz > 1.5 ->
                MechanicalDefectType.BEARING_RACE_DEFECT_BPFO
            ratio2x > 0.45 && spectrum.peak2xAmplitudeMmS > 2.5 ->
                MechanicalDefectType.SHAFT_MISALIGNMENT_2X
            ratio1x > 0.65 && spectrum.peak1xAmplitudeMmS > 3.0 ->
                MechanicalDefectType.ROTOR_UNBALANCE_1X
            vectorVelocityMmS >= 4.5 ->
                MechanicalDefectType.STRUCTURAL_LOOSENESS
            else ->
                MechanicalDefectType.NORMAL
        }
    }

    /**
     * Synthesizes full industrial diagnostic report.
     */
    fun generateReport(
        triaxial: TriaxialVibration,
        spectrum: HarmonicSpectrum,
        kurtosis: Double = 3.2
    ): VibrationDiagnosticReport {
        val totalVelocity = triaxial.vectorSumVelocityRms
        val zone = classifyIso10816Zone(totalVelocity)
        val defect = diagnoseDefect(spectrum, totalVelocity)

        val predictedHours = when (zone) {
            Iso10816Zone.ZONE_A -> 8760 // > 1 year
            Iso10816Zone.ZONE_B -> 4380 // ~6 months
            Iso10816Zone.ZONE_C -> 480  // 20 days
            Iso10816Zone.ZONE_D -> 24   // 1 day / emergency shutdown
        }

        val recommendation = when (defect) {
            MechanicalDefectType.NORMAL -> "[OK] Machine operating within ISO 10816 Zone A/B parameters."
            MechanicalDefectType.ROTOR_UNBALANCE_1X -> "[ACTION] Dynamic rotor balancing required on 1X harmonic peak."
            MechanicalDefectType.SHAFT_MISALIGNMENT_2X -> "[ACTION] Laser alignment check required for coupling and shafts."
            MechanicalDefectType.BEARING_RACE_DEFECT_BPFO -> "[CRITICAL] Bearing race spalling detected; inspect lubrication and schedule replacement."
            MechanicalDefectType.STRUCTURAL_LOOSENESS -> "[WARNING] Foundation bolting or structural resonance looseness detected."
        }

        return VibrationDiagnosticReport(
            triaxialData = triaxial,
            isoZone = zone,
            primaryDefect = defect,
            predictedHoursToTrip = predictedHours,
            kurtosisFactor = kurtosis,
            recommendationSummary = recommendation
        )
    }
}
