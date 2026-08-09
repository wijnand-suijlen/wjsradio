package nl.wijnand.radio

data class Station(
    val id: String,
    val name: String,
    val broadcaster: String,
    val country: String,
    val url: String,
)

// Curated list of public/reputable broadcasters. Streams verified 2026-08.
object Stations {
    val all = listOf(
        // Nederland
        Station("npo1", "NPO Radio 1", "NPO", "Nederland", "https://icecast.omroep.nl/radio1-bb-mp3"),
        Station("npo2", "NPO Radio 2", "NPO", "Nederland", "https://icecast.omroep.nl/radio2-bb-mp3"),
        Station("npo3", "NPO 3FM", "NPO", "Nederland", "https://icecast.omroep.nl/3fm-bb-mp3"),
        Station("npo4", "NPO Klassiek", "NPO", "Nederland", "https://icecast.omroep.nl/radio4-bb-mp3"),
        Station("npo5", "NPO Radio 5", "NPO", "Nederland", "https://icecast.omroep.nl/radio5-bb-mp3"),
        Station("bnr", "BNR Nieuwsradio", "BNR", "Nederland", "https://stream.bnr.nl/bnr_mp3_128_20"),

        // België
        Station("vrt1", "Radio 1", "VRT", "België", "https://icecast.vrtcdn.be/radio1-high.mp3"),
        Station("klara", "Klara", "VRT", "België", "https://icecast.vrtcdn.be/klara-high.mp3"),
        Station("klaracont", "Klara Continuo", "VRT", "België", "https://icecast.vrtcdn.be/klaracontinuo-high.mp3"),
        Station("rtbf1", "La Première", "RTBF", "België", "https://radios.rtbf.be/laprem1ere-128.mp3"),
        Station("musiq3", "Musiq3", "RTBF", "België", "https://radios.rtbf.be/musiq3-128.mp3"),

        // Duitsland
        Station("wdr2", "WDR 2", "WDR", "Duitsland", "https://wdr-wdr2-rheinland.icecastssl.wdr.de/wdr/wdr2/rheinland/mp3/128/stream.mp3"),
        Station("wdr3", "WDR 3", "WDR", "Duitsland", "https://wdr-wdr3-live.icecastssl.wdr.de/wdr/wdr3/live/mp3/256/stream.mp3"),
        Station("wdr5", "WDR 5", "WDR", "Duitsland", "https://wdr-wdr5-live.icecastssl.wdr.de/wdr/wdr5/live/mp3/128/stream.mp3"),
        Station("dlf", "Deutschlandfunk", "Deutschlandradio", "Duitsland", "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3"),
        Station("dlfkultur", "Deutschlandfunk Kultur", "Deutschlandradio", "Duitsland", "https://st02.sslstream.dlf.de/dlf/02/128/mp3/stream.mp3"),

        // Frankrijk
        Station("finter", "France Inter", "Radio France", "Frankrijk", "https://icecast.radiofrance.fr/franceinter-midfi.mp3"),
        Station("fculture", "France Culture", "Radio France", "Frankrijk", "https://icecast.radiofrance.fr/franceculture-midfi.mp3"),
        Station("fmusique", "France Musique", "Radio France", "Frankrijk", "https://icecast.radiofrance.fr/francemusique-midfi.mp3"),
        Station("finfo", "franceinfo", "Radio France", "Frankrijk", "https://icecast.radiofrance.fr/franceinfo-midfi.mp3"),
        Station("fip", "FIP", "Radio France", "Frankrijk", "https://icecast.radiofrance.fr/fip-midfi.mp3"),
        Station("fbidf", "France Bleu Paris", "Radio France", "Frankrijk", "https://icecast.radiofrance.fr/fb1071-midfi.mp3"),
        Station("rtl", "RTL", "RTL France", "Frankrijk", "https://icecast.rtl.fr/rtl-1-44-128"),

        // Verenigd Koninkrijk — stabiele BBC master-playlists (de pool_NNN-URL's van
        // akamaized roteren en gaan na verloop van tijd dood)
        Station("bbcws", "BBC World Service", "BBC", "Verenigd Koninkrijk",
            "https://a.files.bbci.co.uk/ms6/live/3441A116-B12E-4D2F-ACA8-C1984642FA4B/audio/simulcast/hls/nonuk/pc_hd_abr_v2/aks/bbc_world_service.m3u8"),
        Station("bbc3", "BBC Radio 3", "BBC", "Verenigd Koninkrijk",
            "https://a.files.bbci.co.uk/ms6/live/3441A116-B12E-4D2F-ACA8-C1984642FA4B/audio/simulcast/hls/nonuk/pc_hd_abr_v2/aks/bbc_radio_three.m3u8"),
        Station("bbc4", "BBC Radio 4", "BBC", "Verenigd Koninkrijk",
            "https://a.files.bbci.co.uk/ms6/live/3441A116-B12E-4D2F-ACA8-C1984642FA4B/audio/simulcast/hls/nonuk/pc_hd_abr_v2/aks/bbc_radio_fourfm.m3u8"),
    )

    val byCountry: Map<String, List<Station>> = all.groupBy { it.country }

    fun byId(id: String): Station? = all.find { it.id == id }
}
