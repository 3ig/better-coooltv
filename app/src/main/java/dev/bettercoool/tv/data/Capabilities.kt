package dev.bettercoool.tv.data

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList

/**
 * The UHD channels are a single HEVC Main10 2160p50 variant (no lower rung to fall back to),
 * so we need a hardware HEVC decoder that does Main10 at 3840x2160.
 */
object Capabilities {
    val uhdHevcMain10: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && !isSoftware(info.name) &&
                    info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) } &&
                    info.getCapabilitiesForType("video/hevc").let { caps ->
                        caps.videoCapabilities?.isSizeSupported(3840, 2160) == true &&
                            caps.profileLevels.any {
                                it.profile == CodecProfileLevel.HEVCProfileMain10 ||
                                    it.profile == CodecProfileLevel.HEVCProfileMain10HDR10
                            }
                    }
            }
        }.getOrDefault(true) // if we can't tell, let the player try
    }

    private fun isSoftware(name: String) =
        name.startsWith("OMX.google.") || name.startsWith("c2.android.") || name.contains(".sw.", ignoreCase = true)
}
