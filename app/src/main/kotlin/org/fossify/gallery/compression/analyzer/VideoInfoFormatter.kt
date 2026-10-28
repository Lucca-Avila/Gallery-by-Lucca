package org.fossify.gallery.compression.analyzer

import android.content.Context
import android.media.MediaFormat
import org.fossify.gallery.R
import java.util.Locale

object VideoInfoFormatter {
    fun format(context: Context, i: VideoInfo): String {
        val s = { id: Int -> context.getString(id) }
        val sb = StringBuilder()

        sb.section(s(R.string.vi_section_general))
        sb.line(s(R.string.vi_container), i.containerMime ?: "?")
        sb.line(s(R.string.vi_file_size), if (i.fileSize > 0) size(i.fileSize) else "?")
        sb.line(s(R.string.vi_duration), duration(i.durationMs))
        sb.line(s(R.string.vi_overall_bitrate), i.overallBitrate?.let { mbps(it) + " (" + s(R.string.vi_calculated) + ")" } ?: "?")

        sb.section(s(R.string.vi_section_video))
        if (i.videoMime == null) {
            sb.line("", s(R.string.vi_no_video))
        } else {
            sb.line(s(R.string.vi_codec), i.videoMime + (i.profile?.let { " (${DecoderCheck.profileName(i.videoMime, it) ?: "profile $it"})" } ?: ""))
            sb.line(s(R.string.vi_resolution), "${i.width}x${i.height}")
            sb.line(s(R.string.vi_rotation), "${i.rotation}° → ${i.effectiveWidth}x${i.effectiveHeight}")
            sb.line(s(R.string.vi_fps), i.fps?.let { String.format(Locale.US, "%.2f", it) + tag(context, i.fpsSource) } ?: "?")
            sb.line(s(R.string.vi_video_bitrate), i.videoBitrate?.let { mbps(it.bps) + tag(context, it.source) } ?: "?")
            val bpp = bitsPerPixel(i)
            sb.line(s(R.string.vi_bpp), bpp?.let { String.format(Locale.US, "%.4f", it) } ?: "?")
            if (i.declaredVideoBitrate != null && i.videoBitrate?.source == ValueSource.MEASURED) {
                sb.line(s(R.string.vi_declared_bitrate), mbps(i.declaredVideoBitrate))
            }
            if (!i.scanned) sb.line("", s(R.string.vi_not_scanned))
            if (i.extraVideoTracks > 0) sb.line(s(R.string.vi_extra_video), i.extraVideoTracks.toString())
        }

        sb.section(s(R.string.vi_section_hdr))
        sb.line(s(R.string.vi_hdr_type), hdrType(context, i))
        sb.line(s(R.string.vi_bit_depth), if (i.likely10Bit) s(R.string.vi_ten_bit) else s(R.string.vi_eight_bit))
        sb.line("color transfer", i.colorTransfer?.toString() ?: "?")
        sb.line("color standard", i.colorStandard?.toString() ?: "?")
        sb.line("color range", i.colorRange?.toString() ?: "?")

        sb.section(s(R.string.vi_section_audio) + " (${i.audio.size})")
        if (i.audio.isEmpty()) sb.line("", s(R.string.vi_none))
        i.audio.forEachIndexed { n, a ->
            val parts = listOfNotNull(
                a.mime,
                a.language ?: "und",
                a.channels?.let { "$it ch" },
                a.sampleRate?.let { "$it Hz" },
                a.bitrate?.let { kbps(it.bps) + tag(context, it.source) },
                if (a.decodable) null else s(R.string.vi_no_decoder),
            )
            sb.line("#${n + 1}", parts.joinToString(", "))
        }

        sb.section(s(R.string.vi_section_subtitles) + " (${i.subtitles.size})")
        if (i.subtitles.isEmpty()) sb.line("", s(R.string.vi_none))
        i.subtitles.forEachIndexed { n, t -> sb.line("#${n + 1}", "${t.mime}, ${t.language ?: "und"}") }

        sb.section(s(R.string.vi_section_codecs))
        i.videoDecoder?.let { sb.line(s(R.string.vi_decoder) + " " + it.mime, support(context, it, true)) }
        i.audio.map { it.mime }.distinct().forEach { m ->
            sb.line(s(R.string.vi_decoder) + " " + m, if (i.audio.first { it.mime == m }.decodable) s(R.string.vi_yes) else s(R.string.vi_no))
        }
        sb.line(s(R.string.vi_encoder) + " HEVC", support(context, i.hevcEncoder, false))
        sb.line(s(R.string.vi_encoder) + " H.264", support(context, i.avcEncoder, false))

        return sb.toString().trimEnd()
    }

    private fun StringBuilder.section(title: String) {
        if (isNotEmpty()) append('\n')
        append("== ").append(title).append(" ==\n")
    }

    private fun StringBuilder.line(label: String, value: String) {
        if (label.isEmpty()) append(value) else append(label).append(": ").append(value)
        append('\n')
    }

    private fun tag(context: Context, source: ValueSource?) = when (source) {
        ValueSource.DECLARED -> " [" + context.getString(R.string.vi_declared) + "]"
        ValueSource.MEASURED -> " [" + context.getString(R.string.vi_measured) + "]"
        ValueSource.ESTIMATED -> " [" + context.getString(R.string.vi_estimated) + "]"
        null -> ""
    }

    private fun support(context: Context, c: CodecSupport, checkProfile: Boolean): String {
        if (!c.supported) {
            val reason = if (checkProfile && !c.profileSupported) " (" + context.getString(R.string.vi_profile_unsupported) + ")" else ""
            return context.getString(R.string.vi_no) + reason
        }
        val kind = context.getString(if (c.hardware) R.string.vi_hardware else R.string.vi_software)
        return context.getString(R.string.vi_yes) + ", $kind (${c.codecName})"
    }

    private fun hdrType(context: Context, i: VideoInfo): String = when {
        i.isDolbyVision -> "Dolby Vision"
        i.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR10 / PQ (ST2084)"
        i.colorTransfer == MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
        i.colorStandard == MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020 (" + context.getString(R.string.vi_wide_gamut) + ")"
        else -> context.getString(R.string.vi_sdr)
    }

    private fun bitsPerPixel(i: VideoInfo): Double? {
        val bitrate = i.videoBitrate?.bps ?: return null
        val fps = i.fps ?: return null
        val pixels = i.effectiveWidth.toDouble() * i.effectiveHeight
        if (pixels <= 0 || fps <= 0f) return null
        return bitrate / (pixels * fps)
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
        else -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    }

    private fun mbps(bps: Long) = String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0)
    private fun kbps(bps: Long) = String.format(Locale.US, "%d kbps", bps / 1000)

    private fun duration(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    }
}
