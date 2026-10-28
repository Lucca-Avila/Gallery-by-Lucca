package org.fossify.gallery.compression.analyzer

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

object DecoderCheck {
    private val codecs by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }

    fun videoDecoder(mime: String, width: Int, height: Int, fps: Float?, profile: Int?): CodecSupport {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            if (fps != null && fps > 0f) setInteger(MediaFormat.KEY_FRAME_RATE, Math.round(fps))
        }
        var best: CodecSupport? = null
        for (info in codecs.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }) {
            val caps = try {
                info.getCapabilitiesForType(mime)
            } catch (e: IllegalArgumentException) {
                continue
            }
            val formatOk = caps.isFormatSupported(format)
            val profileOk = profile == null || caps.profileLevels.any { it.profile == profile }
            val candidate = CodecSupport(mime, formatOk && profileOk, info.isHardwareAccelerated, info.name, profileOk)
            if (best == null || score(candidate) > score(best)) best = candidate
        }
        return best ?: CodecSupport(mime, false, false, null)
    }

    fun audioDecoder(mime: String) = anyCodec(mime, false)

    fun encoder(mime: String) = anyCodec(mime, true)

    private fun anyCodec(mime: String, encoder: Boolean): CodecSupport {
        var best: CodecSupport? = null
        for (info in codecs.filter { it.isEncoder == encoder && it.supportedTypes.any { t -> t.equals(mime, true) } }) {
            val candidate = CodecSupport(mime, true, info.isHardwareAccelerated, info.name)
            if (best == null || score(candidate) > score(best)) best = candidate
        }
        return best ?: CodecSupport(mime, false, false, null)
    }

    // supported beats unsupported; hardware beats software
    private fun score(c: CodecSupport) = (if (c.supported) 2 else 0) + (if (c.hardware) 1 else 0)

    fun is10BitProfile(mime: String, profile: Int): Boolean = when (mime.lowercase()) {
        MediaFormat.MIMETYPE_VIDEO_HEVC -> profile in setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        )
        MediaFormat.MIMETYPE_VIDEO_VP9 -> profile in setOf(
            MediaCodecInfo.CodecProfileLevel.VP9Profile2,
            MediaCodecInfo.CodecProfileLevel.VP9Profile3,
            MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
            MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR,
            MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus,
            MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus,
        )
        MediaFormat.MIMETYPE_VIDEO_AV1 -> profile in setOf(
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus,
        )
        MediaFormat.MIMETYPE_VIDEO_AVC -> profile in setOf(
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444,
        )
        else -> false
    }

    fun profileName(mime: String, profile: Int): String? {
        fun n(vararg pairs: Pair<Int, String>) = pairs.toMap()[profile]
        return when (mime.lowercase()) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> n(
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline to "AVC Baseline",
                MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline to "AVC Constrained Baseline",
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain to "AVC Main",
                MediaCodecInfo.CodecProfileLevel.AVCProfileExtended to "AVC Extended",
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh to "AVC High",
                MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh to "AVC Constrained High",
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 to "AVC High 10",
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 to "AVC High 4:2:2",
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 to "AVC High 4:4:4",
            )
            MediaFormat.MIMETYPE_VIDEO_HEVC -> n(
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain to "HEVC Main",
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 to "HEVC Main 10",
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill to "HEVC Main Still Picture",
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 to "HEVC Main 10 HDR10",
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus to "HEVC Main 10 HDR10+",
            )
            MediaFormat.MIMETYPE_VIDEO_VP9 -> n(
                MediaCodecInfo.CodecProfileLevel.VP9Profile0 to "VP9 Profile 0",
                MediaCodecInfo.CodecProfileLevel.VP9Profile1 to "VP9 Profile 1",
                MediaCodecInfo.CodecProfileLevel.VP9Profile2 to "VP9 Profile 2 (10 bits)",
                MediaCodecInfo.CodecProfileLevel.VP9Profile3 to "VP9 Profile 3",
                MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR to "VP9 Profile 2 HDR",
                MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus to "VP9 Profile 2 HDR10+",
            )
            MediaFormat.MIMETYPE_VIDEO_AV1 -> n(
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8 to "AV1 Main 8",
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 to "AV1 Main 10",
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 to "AV1 Main 10 HDR10",
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus to "AV1 Main 10 HDR10+",
            )
            else -> null
        }
    }
}
