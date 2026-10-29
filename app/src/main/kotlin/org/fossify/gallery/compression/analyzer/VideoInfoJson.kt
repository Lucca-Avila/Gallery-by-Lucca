package org.fossify.gallery.compression.analyzer

import org.json.JSONArray
import org.json.JSONObject

/**
 * [VideoInfo] <-> JSON para o cache de análise. Escrito à mão (sem Gson) para os nomes dos campos não dependerem de
 * ofuscação: o cache sobrevive a atualizações do app. Mude [SCHEMA] ao alterar [VideoInfo] ou esta classe.
 */
object VideoInfoJson {
    // 2: a análise padrão passou a ser estimativa (as entradas antigas vinham da varredura completa).
    const val SCHEMA = 2

    fun toJson(info: VideoInfo): String = JSONObject().apply {
        put("containerMime", info.containerMime)
        put("fileSize", info.fileSize)
        put("durationMs", info.durationMs)
        put("overallBitrate", info.overallBitrate)
        put("videoMime", info.videoMime)
        put("width", info.width)
        put("height", info.height)
        put("rotation", info.rotation)
        put("profile", info.profile)
        put("fps", info.fps?.toDouble())
        put("fpsSource", info.fpsSource?.name)
        put("videoBitrate", info.videoBitrate?.let(::bitrate))
        put("declaredVideoBitrate", info.declaredVideoBitrate)
        put("colorTransfer", info.colorTransfer)
        put("colorStandard", info.colorStandard)
        put("colorRange", info.colorRange)
        put("isDolbyVision", info.isDolbyVision)
        put("likely10Bit", info.likely10Bit)
        put("extraVideoTracks", info.extraVideoTracks)
        put("audio", JSONArray(info.audio.map(::audio)))
        put("subtitles", JSONArray(info.subtitles.map { JSONObject().put("mime", it.mime).put("language", it.language) }))
        put("videoDecoder", info.videoDecoder?.let(::codec))
        put("hevcEncoder", codec(info.hevcEncoder))
        put("avcEncoder", codec(info.avcEncoder))
        put("scanned", info.scanned)
    }.toString()

    /** null se o texto estiver corrompido ou incompleto (o cache trata como se não existisse). */
    fun fromJson(text: String): VideoInfo? = try {
        val o = JSONObject(text)
        VideoInfo(
            containerMime = o.stringOrNull("containerMime"),
            fileSize = o.getLong("fileSize"),
            durationMs = o.getLong("durationMs"),
            overallBitrate = o.longOrNull("overallBitrate"),
            videoMime = o.stringOrNull("videoMime"),
            width = o.getInt("width"),
            height = o.getInt("height"),
            rotation = o.getInt("rotation"),
            profile = o.intOrNull("profile"),
            fps = o.doubleOrNull("fps")?.toFloat(),
            fpsSource = o.stringOrNull("fpsSource")?.let { ValueSource.valueOf(it) },
            videoBitrate = o.objectOrNull("videoBitrate")?.let(::bitrate),
            declaredVideoBitrate = o.longOrNull("declaredVideoBitrate"),
            colorTransfer = o.intOrNull("colorTransfer"),
            colorStandard = o.intOrNull("colorStandard"),
            colorRange = o.intOrNull("colorRange"),
            isDolbyVision = o.getBoolean("isDolbyVision"),
            likely10Bit = o.getBoolean("likely10Bit"),
            extraVideoTracks = o.getInt("extraVideoTracks"),
            audio = o.getJSONArray("audio").objects().map(::audio),
            subtitles = o.getJSONArray("subtitles").objects().map { SubtitleTrackInfo(it.getString("mime"), it.stringOrNull("language")) },
            videoDecoder = o.objectOrNull("videoDecoder")?.let(::codec),
            hevcEncoder = codec(o.getJSONObject("hevcEncoder")),
            avcEncoder = codec(o.getJSONObject("avcEncoder")),
            scanned = o.getBoolean("scanned"),
        )
    } catch (e: Exception) {
        null
    }

    private fun bitrate(b: Bitrate) = JSONObject().put("bps", b.bps).put("source", b.source.name)
    private fun bitrate(o: JSONObject) = Bitrate(o.getLong("bps"), ValueSource.valueOf(o.getString("source")))

    private fun audio(a: AudioTrackInfo) = JSONObject()
        .put("mime", a.mime).put("language", a.language).put("channels", a.channels).put("sampleRate", a.sampleRate)
        .put("bitrate", a.bitrate?.let(::bitrate)).put("decodable", a.decodable)

    private fun audio(o: JSONObject) = AudioTrackInfo(
        mime = o.getString("mime"), language = o.stringOrNull("language"), channels = o.intOrNull("channels"),
        sampleRate = o.intOrNull("sampleRate"), bitrate = o.objectOrNull("bitrate")?.let(::bitrate), decodable = o.getBoolean("decodable"),
    )

    private fun codec(c: CodecSupport) = JSONObject()
        .put("mime", c.mime).put("supported", c.supported).put("hardware", c.hardware).put("codecName", c.codecName)
        .put("profileSupported", c.profileSupported)

    private fun codec(o: JSONObject) = CodecSupport(
        mime = o.getString("mime"), supported = o.getBoolean("supported"), hardware = o.getBoolean("hardware"),
        codecName = o.stringOrNull("codecName"), profileSupported = o.getBoolean("profileSupported"),
    )

    private fun JSONObject.stringOrNull(k: String) = if (isNull(k)) null else getString(k)
    private fun JSONObject.longOrNull(k: String) = if (isNull(k)) null else getLong(k)
    private fun JSONObject.intOrNull(k: String) = if (isNull(k)) null else getInt(k)
    private fun JSONObject.doubleOrNull(k: String) = if (isNull(k)) null else getDouble(k)
    private fun JSONObject.objectOrNull(k: String) = if (isNull(k)) null else getJSONObject(k)
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
