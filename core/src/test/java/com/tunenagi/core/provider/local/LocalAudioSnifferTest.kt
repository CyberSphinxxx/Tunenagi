package com.tunenagi.core.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sniffer decides what a file is from its own first bytes, so its rules are asserted byte by
 * byte rather than through a resolver.
 *
 * What is being checked is not only the happy path. Every case below that returns
 * [SniffedContainer.Unknown] is a case where a *plausible* heuristic would have guessed: a `ftyp`
 * box that is actually a video brand, a `RIFF` container that is not `WAVE`, a signature that
 * appears at the wrong offset, or a header too short to contain the signature at all. Guessing in
 * those cases is how a decoder gets handed something it cannot open, and `SPEC/PROVIDERS.md`
 * requires the honest answer instead.
 */
class LocalAudioSnifferTest {

    private fun header(vararg leading: Int): ByteArray =
        ByteArray(LocalAudioSniffer.HEADER_BYTES) { index ->
            if (index < leading.size) leading[index].toByte() else 0
        }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String, at: Int = 0, size: Int = LocalAudioSniffer.HEADER_BYTES): ByteArray =
        ByteArray(size) { index ->
            val offset = index - at
            if (offset in text.indices) text[offset].code.toByte() else 0
        }

    private fun recognised(header: ByteArray): LocalAudioContainer? =
        (LocalAudioSniffer.sniff(header) as? SniffedContainer.Recognised)?.container

    /** An ISO base media file: the box type at offset 4 and the brand at offset 8. */
    private fun isoBaseMedia(brand: String): ByteArray {
        val header = ByteArray(LocalAudioSniffer.HEADER_BYTES)
        "ftyp".forEachIndexed { index, character -> header[4 + index] = character.code.toByte() }
        brand.forEachIndexed { index, character -> header[8 + index] = character.code.toByte() }
        return header
    }

    @Test
    fun everySignatureTheAppKnowsIsRecognised() {
        assertEquals(LocalAudioContainer.Flac, recognised(header(0x66, 0x4C, 0x61, 0x43)))
        assertEquals(LocalAudioContainer.Ogg, recognised(header(0x4F, 0x67, 0x67, 0x53)))
        assertEquals(LocalAudioContainer.Midi, recognised(header(0x4D, 0x54, 0x68, 0x64)))
        assertEquals(LocalAudioContainer.Wav, recognised(header(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45)))
        assertEquals(LocalAudioContainer.Mp3, recognised(ascii("ID3")))
    }

    /** An ISO base media file carries `ftyp` at offset 4, never at 0: the first four bytes are a size. */
    @Test
    fun anIsoBaseMediaFileIsRecognisedByTheBoxAtOffsetFour() {
        assertEquals(LocalAudioContainer.Mp4Audio, recognised(ascii("ftyp", at = 4)))
    }

    /**
     * The one place a declared brand is used. An `ftyp` box is shared by audio and video, so a brand
     * that is explicitly a movie is not treated as the user's music — while the audio brand of the
     * same box is.
     */
    @Test
    fun anFtypBoxWithAVideoBrandIsNotTreatedAsAudio() {
        assertEquals(LocalAudioContainer.Mp4Audio, recognised(isoBaseMedia("M4A ")))
        assertEquals(SniffedContainer.Unknown, LocalAudioSniffer.sniff(isoBaseMedia("mp42")))
        assertEquals(SniffedContainer.Unknown, LocalAudioSniffer.sniff(isoBaseMedia("qt  ")))
    }

    /** `ftyp` exists in the file, so the size of the box before it must not be read as a signature. */
    @Test
    fun anFtypSignatureAtTheWrongOffsetIsNotRecognised() {
        assertEquals(SniffedContainer.Unknown, LocalAudioSniffer.sniff(ascii("ftyp")))
    }

    @Test
    fun aRiffContainerThatIsNotWaveIsNotAudio() {
        val avi = ByteArray(LocalAudioSniffer.HEADER_BYTES)
        "RIFF".forEachIndexed { index, character -> avi[index] = character.code.toByte() }
        "AVI ".forEachIndexed { index, character -> avi[8 + index] = character.code.toByte() }

        assertEquals(SniffedContainer.Unknown, LocalAudioSniffer.sniff(avi))
    }

    @Test
    fun anAiffFileIsRecognisedInBothOfItsForms() {
        val aiff = ByteArray(LocalAudioSniffer.HEADER_BYTES)
        "FORM".forEachIndexed { index, character -> aiff[index] = character.code.toByte() }
        "AIFF".forEachIndexed { index, character -> aiff[8 + index] = character.code.toByte() }
        val aifc = aiff.copyOf().also { it[11] = 'C'.code.toByte() }

        assertEquals(LocalAudioContainer.Aiff, recognised(aiff))
        assertEquals(LocalAudioContainer.Aiff, recognised(aifc))
    }

    /**
     * MPEG audio and ADTS AAC share the eleven-bit sync word; the layer field is what separates
     * them. Getting this backwards would hand a raw AAC frame to the MPEG audio parser.
     */
    @Test
    fun mpegAudioAndAdtsAacAreToldApartByTheLayerField() {
        // 0xFB: sync set, layer III (bits 1-2 non-zero).
        assertEquals(LocalAudioContainer.Mp3, recognised(bytes(0xFF, 0xFB, 0x90, 0x00)))
        // 0xF1 and 0xF9: sync set, layer field 00 - ADTS.
        assertEquals(LocalAudioContainer.AdtsAac, recognised(bytes(0xFF, 0xF1)))
        assertEquals(LocalAudioContainer.AdtsAac, recognised(bytes(0xFF, 0xF9)))
        assertNotEquals(
            "a frame with a reserved layer is not MPEG audio",
            LocalAudioContainer.Mp3,
            recognised(bytes(0xFF, 0xE1))
        )
    }

    @Test
    fun aFileThatOpensWithNoBytesIsEmptyRatherThanUnknown() {
        assertEquals(SniffedContainer.Empty, LocalAudioSniffer.sniff(ByteArray(0)))
    }

    @Test
    fun aHeaderTooShortToHoldASignatureIsUnknown() {
        assertEquals(SniffedContainer.Unknown, LocalAudioSniffer.sniff(bytes(0x66, 0x4C)))
        assertEquals(SniffedContainer.Unknown, LocalAudioSniffer.sniff(ascii("RIFF", size = 6)))
    }

    /** A four-byte read is enough for the containers whose signature starts at 0. */
    @Test
    fun aSignatureAtZeroIsRecognisedWithoutAWholeHeader() {
        assertEquals(LocalAudioContainer.Flac, recognised(bytes(0x66, 0x4C, 0x61, 0x43)))
    }

    @Test
    fun textAndOtherNonAudioBytesAreUnknown() {
        assertEquals(SniffedContainer.Unknown, LocalAudioSniffer.sniff(ascii("this is a readme")))
        assertEquals(SniffedContainer.Unknown, LocalAudioSniffer.sniff(bytes(0x00, 0x00, 0x00, 0x00)))
    }

    /** ASF/WMA is recognised *and* unplayable here: that distinction is the point of the enum. */
    @Test
    fun aWindowsMediaFileIsRecognisedAndIsNotInThePlayableMatrix() {
        val container = recognised(header(0x30, 0x26, 0xB2, 0x75))

        assertEquals(LocalAudioContainer.Asf, container)
        assertTrue("WMA must not be advertised as playable", container !in LocalAudioContainer.PLAYABLE)
    }

    @Test
    fun thePlayableMatrixCoversTheFormatsTheAppShips() {
        assertEquals(
            setOf(
                LocalAudioContainer.Mp3,
                LocalAudioContainer.Flac,
                LocalAudioContainer.Mp4Audio,
                LocalAudioContainer.Ogg,
                LocalAudioContainer.Wav,
                LocalAudioContainer.AdtsAac
            ),
            LocalAudioContainer.PLAYABLE
        )
    }

    /** Sixteen bytes is the contract callers read; a bigger read would be wasted work per file. */
    @Test
    fun theHeaderContractIsSixteenBytes() {
        assertEquals(16, LocalAudioSniffer.HEADER_BYTES)
    }
}
