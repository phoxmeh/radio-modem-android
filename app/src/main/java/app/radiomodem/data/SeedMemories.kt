package app.radiomodem.data

import app.radiomodem.schedule.FaxStation
import app.radiomodem.schedule.FaxStations

/**
 * Starter lists, bumped via [VERSION] so installed copies are refreshed by [MemoryStore].
 *
 *  - PSK31 USB dial frequencies: RSGB "PSK31 – work the world with low power" (rsgb.org/main/?p=4930).
 *  - HF weather fax: NOAA/NWS "Worldwide Marine Radiofacsimile Broadcast Schedules", 7 March 2025
 *    (weather.gov/media/marine/rfax.pdf). Frequencies there are assigned (centre) frequencies unless marked, and
 *    NOAA's rule is "for carrier frequency subtract 1.9 kHz", so the USB dial is assigned - 1.9 kHz and the fax
 *    subcarrier lands at 1900 Hz. Athens and Bangkok are marked as carrier frequencies, so their dial is the
 *    listed value. Stations NOAA reports as discontinued (Wellington NZ 2023; Iqaluit, Resolute and Inuvik 2019;
 *    Pevek, a 1997 LF entry) are left out. The NWS has also proposed ending its own five US broadcasts toward
 *    the end of 2026 (PNS 26-48).
 */
object SeedMemories {
    const val VERSION = 6  // 6: SSTV calling frequencies, 5: Shortwave Radiogram entries (4: entries use the rig's default mode and bandwidth)

    private fun psk(band: String, dialKhz: Double, note: String = "") = Memory(
        name = "$band PSK31", group = "PSK31 calling", freqHz = Math.round(dialKhz * 1000), mode = "",
        modem = "BPSK31", carrierHz = 1000, notes = note, seedKey = "psk31-$band",
    )

    private fun sstv(name: String, dialKhz: Double, mode: String, note: String) = Memory(
        name = name, group = "SSTV", freqHz = Math.round(dialKhz * 1000), mode = mode, modem = "SSTV Auto", carrierHz = 1700,
        notes = note, seedKey = "sstv-${dialKhz}",
    )

    private fun kHz(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString().trimEnd('0')

    private fun entries(s: FaxStation): List<Memory> = s.freqs.map { fq ->
        val f = fq.kHz
        Memory(
            name = "${s.place}, ${s.country} · ${kHz(f)} kHz",
            group = "Weather fax: ${s.region}",
            freqHz = Math.round(s.dialKHz(fq) * 1000), mode = "", modem = "WEFAX", carrierHz = 1900,
            // Most useful facts first: the list shows only the first two lines of the notes.
            notes = buildString {
                append("${s.operator} (call sign ${s.call}). ")
                if (fq.hours.isNotEmpty()) append("Hours: ${fq.hours}. ")
                if (s.note.isNotEmpty()) append(s.note + " ")
                append("Schedule info dated ${s.dated}. ")
                append(if (s.listedIsCarrier) "Published as a carrier frequency, so the dial is the listed value."
                       else "Dial is 1.9 kHz below the published frequency (USB, fax subcarrier at 1900 Hz).")
            },
            seedKey = "fax-${s.keyPrefix}-$f",
        )
    }

    val all: List<Memory> = listOf(
        psk("160m", 1838.150), psk("80m", 3580.150),
        psk("40m", 7070.150, "RSGB lists 7040 kHz and up; 7070 is common in the Americas."),
        psk("30m", 10142.150), psk("20m", 14070.150), psk("17m", 18100.150),
        psk("15m", 21080.150), psk("12m", 24920.150), psk("10m", 28120.150),
    ) + FaxStations.all.flatMap { entries(it) } +
        app.radiomodem.radiogram.RadiogramEntries.memories(app.radiomodem.radiogram.RadiogramEntries.bundledSlots, seeds = true) + listOf(
        sstv("SSTV · 20 m · 14.230 MHz", 14230.0, "", "The main SSTV calling frequency (USB). Martin M1, Scottie S1 and Robot 36 are the usual modes."),
        sstv("SSTV · 15 m · 21.340 MHz", 21340.0, "", "SSTV calling frequency (USB)."),
        sstv("SSTV · 10 m · 28.680 MHz", 28680.0, "", "SSTV calling frequency (USB)."),
        sstv("SSTV · 40 m · 7.171 MHz", 7171.0, "LSB", "SSTV calling frequency, lower sideband by convention below 10 MHz."),
        sstv("SSTV · 80 m · 3.845 MHz", 3845.0, "LSB", "SSTV calling frequency, lower sideband by convention below 10 MHz."),
        sstv("SSTV · 2 m · 145.500 MHz", 145500.0, "FM", "VHF SSTV calling frequency (FM)."),
        sstv("SSTV · ISS · 145.800 MHz", 145800.0, "FM", "ARISS SSTV downlink from the International Space Station during events (FM). PD120 or PD180 are typical there; only Martin, Scottie and Robot 36 decode so far."),
    )
}
