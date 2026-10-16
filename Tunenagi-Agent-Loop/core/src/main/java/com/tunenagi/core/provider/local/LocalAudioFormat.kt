package com.tunenagi.core.provider.local

/**
 * What a local file's container is, and whether this app plays it — owned.
 *
 * The list is a *policy*, not a promise about the hardware. `SPEC/AUDIO_ENGINE.md` says local format
 * support "follows Android Media3/decoders", and `SPEC/PROVIDERS.md` requires unsupported media to
 * be reported as such rather than transcoded or silently skipped. So the app declares the matrix it
 * ships (the formats the platform decoders have covered since the project's minimum API) and reports
 * anything else as [com.tunenagi.core.model.ProviderError.UnsupportedFormat] instead of handing a
 * decoder a container it cannot open and hoping for a readable failure.
 *
 * [label] is for a human reading a diagnostic; it is never matched on.
 */
enum class LocalAudioContainer(val mimeType: String, val label: String) {
    Mp3("audio/mpeg", "MP3"),
    Flac("audio/flac", "FLAC"),
    Mp4Audio("audio/mp4", "MP4/M4A"),
    Ogg("audio/ogg", "Ogg"),
    Wav("audio/wav", "WAV"),
    AdtsAac("audio/aac", "AAC (ADTS)"),

    // Recognised on purpose, and deliberately *not* playable here: a file this app can identify but
    // not play deserves a better answer than "unknown". Each one is a real format a user's library
    // can contain, and WMA in particular is what a Windows media folder tends to add.
    Aiff("audio/aiff", "AIFF"),
    Asf("audio/x-ms-wma", "WMA/ASF"),
    Midi("audio/midi", "MIDI");

    companion object {
        /**
         * The containers this app declares it can play.
         *
         * `AdtsAac` is included because the platform decoder handles raw ADTS streams; `Aiff`, `Asf`
         * and `Midi` are excluded because none of them is guaranteed across the supported API range.
         * Adding one is a product decision, which is why the set is named here rather than being
         * inferred from the enum's declaration order.
         */
        val PLAYABLE: Set<LocalAudioContainer> = setOf(Mp3, Flac, Mp4Audio, Ogg, Wav, AdtsAac)
    }
}

/** What the first bytes of a file identified. */
sealed interface SniffedContainer {

    /** A container the app recognised. Playability is a separate question — see [LocalAudioContainer.PLAYABLE]. */
    data class Recognised(val container: LocalAudioContainer) : SniffedContainer

    /** Real bytes, but nothing in them says this is a container the app knows. */
    data object Unknown : SniffedContainer

    /** The file opened but offered no bytes at all: a truncated or empty file, not a format question. */
    data object Empty : SniffedContainer
}

/**
 * Identifies a container from the file's own header — owned.
 *
 * The declared content type and the file extension are *hints*, and this project treats them as
 * such: a `content://` row with `audio/mpeg` and the bytes of a WMA file is a file the user cannot
 * play, and believing the label would produce a decoder error deep in playback instead of an honest
 * answer at resolve time. So the bytes decide, and a caller may only use a declared type to explain
 * a mismatch, never to override it.
 *
 * A header is all this reads: [HEADER_BYTES] bytes are enough for every container below, which is
 * what keeps resolution cheap on a library of thousands of files. Deciding a container is *not* the
 * same as decoding it — a header can be right about the container and the file still be unreadable
 * — so an unsupported result here means "do not even try", never "this will definitely play".
 */
object LocalAudioSniffer {

    /**
     * How many bytes a caller must read for [sniff] to be able to answer.
     *
     * The largest signature requirement is `RIFF` + four size bytes + `WAVE` (12) and `ftyp` at
     * offset 4 (8); 16 leaves room without reading anything like the whole file.
     */
    const val HEADER_BYTES = 16

    /** The signature an ASF/WMA file always starts with. */
    private val ASF = intArrayOf(0x30, 0x26, 0xB2, 0x75)

    fun sniff(header: ByteArray): SniffedContainer = when {
        header.isEmpty() -> SniffedContainer.Empty

        // FLAC first: its signature is four bytes at offset 0, and no other container here starts
        // with it.
        header.ascii(0, "fLaC") -> recognised(LocalAudioContainer.Flac)
        header.ascii(0, "OggS") -> recognised(LocalAudioContainer.Ogg)
        header.ascii(0, "MThd") -> recognised(LocalAudioContainer.Midi)

        // AIFF is an IFF file: "FORM" + a size + the form type, and AIFC is the compressed variant
        // of the same container.
        header.ascii(0, "FORM") && (header.ascii(8, "AIFF") || header.ascii(8, "AIFC")) ->
            recognised(LocalAudioContainer.Aiff)

        // A RIFF file that is not WAVE is not audio (an AVI video, for instance): it is deliberately
        // left Unknown rather than mapped onto the nearest thing.
        header.ascii(0, "RIFF") && header.ascii(8, "WAVE") -> recognised(LocalAudioContainer.Wav)

        // ISO base media files (`ftyp` at offset 4) are one container for audio and video alike. The
        // declared type is what separates an M4A from an MP4 movie, and this is the one place where
        // it is used: a `video/` declaration is not this app's media, and it is not treated as audio.
        header.ascii(4, "ftyp") && !header.declaresVideo() -> recognised(LocalAudioContainer.Mp4Audio)

        header.bytes(0, ASF) -> recognised(LocalAudioContainer.Asf)

        // An ID3v2 tag is a MPEG audio file as far as a header can tell; only the decoder can be
        // sure, and the tag is not a reason to refuse the file.
        header.ascii(0, "ID3") -> recognised(LocalAudioContainer.Mp3)

        header.isMpegFrame() -> recognised(LocalAudioContainer.Mp3)
        header.isAdtsFrame() -> recognised(LocalAudioContainer.AdtsAac)

        else -> SniffedContainer.Unknown
    }

    private fun recognised(container: LocalAudioContainer) = SniffedContainer.Recognised(container)

    /**
     * An `ftyp` box declares its brand at offset 8..12: `M4A `, `isom`, `mp42`, `qt  ` and others.
     * Only an explicit video brand is rejected here — everything else with an `ftyp` box is treated
     * as the media the user's library holds, which is what a file in a music folder is.
     */
    private fun ByteArray.declaresVideo(): Boolean =
        ascii(8, "mp41") || ascii(8, "mp42") || ascii(8, "avc1") || ascii(8, "qt  ")

    /** MPEG audio frame sync: eleven set bits, then a layer that is not `reserved`. */
    private fun ByteArray.isMpegFrame(): Boolean {
        if (size < 2) return false
        val first = this[0].toInt() and 0xFF
        val second = this[1].toInt() and 0xFF
        return first == 0xFF && (second and 0xE0) == 0xE0 && (second and 0x06) != 0
    }

    /**
     * ADTS AAC frame sync. The distinguishing bits are the layer field: ADTS carries `00` where every
     * MPEG audio layer is non-zero, which is why this is checked *after* [isMpegFrame].
     */
    private fun ByteArray.isAdtsFrame(): Boolean {
        if (size < 2) return false
        val first = this[0].toInt() and 0xFF
        val second = this[1].toInt() and 0xFF
        return first == 0xFF && (second and 0xF6) == 0xF0
    }

    private fun ByteArray.ascii(offset: Int, text: String): Boolean {
        if (offset + text.length > size) return false
        for (index in text.indices) {
            if (this[offset + index].toInt() and 0xFF != text[index].code) return false
        }
        return true
    }

    private fun ByteArray.bytes(offset: Int, expected: IntArray): Boolean {
        if (offset + expected.size > size) return false
        for (index in expected.indices) {
            if (this[offset + index].toInt() and 0xFF != expected[index]) return false
        }
        return true
    }
}
