package com.kodi.cloudstream

import android.content.Context
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.Vidara
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AresHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AresHD())

        // Mirrors frecuentes en AresHD que pueden variar de dominio respecto a los incluidos por defecto
        registerExtractorAPI(VidaraMirrorCzzbgnw())
        registerExtractorAPI(VoeAltcha())
        registerExtractorAPI(VoeAltchaCasey())
        registerExtractorAPI(VoeAltchaMorencius())
        registerExtractorAPI(DoodMirrorPlaymogo())
        registerExtractorAPI(StreamWishMirrorHgplaycdn())
        registerExtractorAPI(StreamWishMirrorHglamioz())
        registerExtractorAPI(StreamWishMirrorNiramirus())
        registerExtractorAPI(StreamWishMirrorPlaynixes())
        registerExtractorAPI(StreamWishMirrorMedixiru())
        registerExtractorAPI(StreamWishMirrorAudinifer())
        registerExtractorAPI(StreamWishMirrorVibuxer())
        registerExtractorAPI(StreamWishMirrorHanerix())
        registerExtractorAPI(VidHideMirror())
    }
}

class VidaraMirrorCzzbgnw : Vidara() {
    override var mainUrl = "https://czzbgnw.com"
}

class DoodMirrorPlaymogo : DoodLaExtractor() {
    override var mainUrl = "https://playmogo.com"
}

class StreamWishMirrorHgplaycdn : StreamWishExtractor() {
    override var mainUrl = "https://hgplaycdn.com"
}

class StreamWishMirrorHglamioz : StreamWishExtractor() {
    override var mainUrl = "https://hglamioz.com"
}

class StreamWishMirrorNiramirus : StreamWishExtractor() {
    override var mainUrl = "https://niramirus.com"
}

class StreamWishMirrorPlaynixes : StreamWishExtractor() {
    override var mainUrl = "https://playnixes.com"
}

class StreamWishMirrorMedixiru : StreamWishExtractor() {
    override var mainUrl = "https://medixiru.com"
}

class StreamWishMirrorAudinifer : StreamWishExtractor() {
    override var mainUrl = "https://audinifer.com"
}

class StreamWishMirrorVibuxer : StreamWishExtractor() {
    override var mainUrl = "https://vibuxer.com"
}

class StreamWishMirrorHanerix : StreamWishExtractor() {
    override var mainUrl = "https://hanerix.com"
}

class VidHideMirror : VidHidePro() {
    override var mainUrl = "https://vidhideplus.com"
}
