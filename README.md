# تطبيق محور الصناعي (`mihwar-modbus-industrial-iot-1290113`)
> منظومة industrial_iot المتكاملة مع ربط 3-Axis Vibration Accelerometer + Modbus RS485 Acoustic Probe

- **Project ID**: `#1290113`
- **Commercial Archetype**: `industrial_iot` (Predictive maintenance, turbine and motor vibration analysis)
- **Bound Hardware Peripheral**: `3-Axis Vibration Accelerometer + Modbus RS485 Acoustic Probe`
- **Package Name**: `sa.factory.mihwarmodbusindust`
- **Zero-Emoji Invariant**: Strictly Enforced (100% Vector Icons)

---

## [Sidekick Autonomous Update]

### 1. Architectural & Production Engines
- **`ModbusRtuAcousticProtocolEngine`**:
  * Implemented Modbus RTU RS-485 serial communication with CRC-16 (polynomial `0xA001`, initial value `0xFFFF`).
  * Built binary request generators for Function Code 0x03 (Read Holding Registers) and automated CRC checksum verification.
  * Designed acoustic telemetry register decoding for ultrasonic bearing friction detection, sound decibels RMS, crest factor, and acoustic emission spikes.
  * Comprehensive exception response frame parsing (`ILLEGAL_FUNCTION`, `ILLEGAL_DATA_ADDRESS`, etc.).

- **`VibrationSpectralPredictiveEngine`**:
  * Triaxial accelerometer vibration analysis ($X$ Axial, $Y$ Horizontal Radial, $Z$ Vertical Radial).
  * ISO 10816 / ISO 20816 machine vibration severity classification (Zone A Optimal, Zone B Acceptable, Zone C Unsatisfactory, Zone D Critical trip).
  * Harmonic spectral analysis with automated diagnostic identification of 1X dynamic rotor imbalance, 2X angular shaft misalignment, and high-frequency bearing race defect frequencies (BPFO).
  * Automated MTTM remaining useful operational hours prediction.

### 2. UI & Driver Modernization
- **`HardwareSensorDriver`**: Bound both industrial engines to stream live Modbus RTU frames, vibration velocity RMS, and acoustic diagnostics into ViewModel StateFlows.
- **`MainActivity` & Compose UI**: Fixed missing typography imports, resolved single-quote template hazards, migrated deprecated `Divider` to `HorizontalDivider`, and eliminated non-standard unicode glyphs (checkmarks replaced with `[OK]`, crosses with `[X]`, bullets with `|`).
- **`MihwarModbusViewModel`**: Enhanced `checkout(paymentMethod: String)` signature for dynamic POS / Mada payment handoff.
- **`AndroidManifest.xml`**: Modernized application label to `محور للحلول الصناعية - Mihwar Modbus IoT`.

### 3. Unit Regression Test Suite
- Comprehensive 10-case JUnit test suite in `sa.factory.mihwarmodbusindust.MihwarModbusRegressionTest`:
  * `test01_modbusCrc16ComputationAndVerification`
  * `test02_modbusReadHoldingRegistersRequestSerialization`
  * `test03_modbusReadResponseSuccessParsing`
  * `test04_modbusExceptionResponseHandling`
  * `test05_modbusCrcErrorDetection`
  * `test06_acousticProbeTelemetryDecoding`
  * `test07_iso10816VibrationZoneClassification`
  * `test08_mechanicalDefectDiagnosticIdentification`
  * `test09_hardwareSensorDriverEmitsValidTelemetry`
  * `test10_repositoryCartAndTaxCalculation`

### 4. Build and Test Instructions
```bash
# Build Debug APK
./gradlew assembleDebug

# Run Unit Regression Tests
./gradlew testDebugUnitTest
```
