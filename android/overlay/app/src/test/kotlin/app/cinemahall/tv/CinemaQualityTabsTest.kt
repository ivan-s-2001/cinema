package app.cinemahall.tv

import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.ui.components.buildSourceAddonTabs
import com.arflix.tv.ui.components.sourceTabId
import com.arflix.tv.ui.screens.details.bestAutoPlayStream
import org.junit.Assert.*
import org.junit.Test

class CinemaQualityTabsTest {
    private fun stream(quality:String,voice:String="Дубляж")=StreamSource(source=voice,addonName="CinemaHall",addonId=CinemaIntegration.ID,quality=quality,size="",url="http://127.0.0.1:18991/play/447301/0/0",rawLabel=quality)
    @Test fun qualityTabsContainOnlyAvailableResolutionsAndGroupVoices(){
        val streams=listOf(stream("Авто"),stream("1080p"),stream("4K"),stream("1080p","Оригинал"),stream("480p"),stream("2K"))
        assertEquals(listOf("4K","2K","1080p","480p"),buildSourceAddonTabs(streams,emptyList()).map {it.label})
        assertEquals(sourceTabId(streams[1]),sourceTabId(streams[3]))
        assertNotEquals(sourceTabId(streams[1]),sourceTabId(streams[2]))
        assertEquals("Авто",bestAutoPlayStream(streams,0)?.rawLabel)
    }
    @Test fun tmdbImagesRequireSafePathsAndCorrectAspectRole(){
        assertEquals("https://image.tmdb.org/t/p/original/cover.jpg",Metadata.tmdbArtwork("/cover.jpg",true))
        assertEquals("https://image.tmdb.org/t/p/w780/poster.png",Metadata.tmdbArtwork("/poster.png",false))
        assertEquals("",Metadata.tmdbArtwork("https://example.com/img.jpg",true))
        assertEquals("",Metadata.tmdbArtwork("/../secret.jpg",false))
    }
    @Test fun defaultAddonOwnsItsNativeIdsAndIncludesBothDeviceCatalogs(){
        val addon=CinemaIntegration.addon()
        assertEquals(listOf("ch:"),addon.manifest!!.idPrefixes)
        assertTrue(addon.manifest!!.types.containsAll(listOf("movie","series")))
        assertEquals(6,CinemaIntegration.catalogs.size)
        assertTrue(CinemaIntegration.catalogs.all {it.addonId==addon.id})
    }
}
