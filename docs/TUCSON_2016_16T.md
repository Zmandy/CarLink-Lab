# Hyundai Tucson TL 1.6T G4FJ notes

These values were confirmed on a personal 2015/2016 Tucson TL 1.6T G4FJ. Other markets, model years and head-unit revisions may differ.

## TPMS

- Request header: 7D6
- Request: 21 06
- Response service: 61 06
- Sensor record: 4-byte ID + pressure + temperature + status + sequence
- Record starts: 2, 10, 18, 26
- Pressure: raw / 4 * 6.894757 kPa
- Temperature: raw - 40 degrees Celsius
- Mapping: record 1-4 to FL, FR, RL, RR

Do not decode pressure or temperature from the sensor ID bytes.

## Fuel

- Preferred PID: 015E
- Fallback: 0110 MAF and 010D speed
- Gasoline assumptions: 14.7 AFR and 745 g/L
- Fuel rate: MAF / 14.7 / 745 * 3600 L/h
- Instant consumption: fuel rate / speed * 100 L/100 km

Fuel pulse width and inferred consumption are estimates and must not be treated as metrologically accurate.

## CarLife local ports

- 7240 control
- 8240 video
- 9240 media
- 9241 TTS
- 9242 VR
- 9340 touch

USB debugging is required on the tested setup. The car head unit uses file transfer or an Android debugging transport to reach the local listeners.

## Dashboard

The final dashboard is loaded from:

app/src/main/assets/tpms_template.html

Android injects data with JavaScript. The page is rendered in an 800x480 WebView and converted to H.264 by H264FrameEncoder.
