package com.simplevideodownloader.adblock

/** Listas de filtros que la app sabe descargar. Las mismas que usan ABP, AdBlock o AdGuard. */
data class FilterList(val id: String, val name: String, val url: String, val enabledByDefault: Boolean)

object FilterLists {
    val all = listOf(
        FilterList("easylist", "EasyList (anuncios)", "https://easylist.to/easylist/easylist.txt", true),
        FilterList("easyprivacy", "EasyPrivacy (rastreadores)", "https://easylist.to/easylist/easyprivacy.txt", true),
        FilterList(
            "easylist_es", "EasyList Spanish",
            "https://easylist-downloads.adblockplus.org/easylistspanish.txt", true,
        ),
        FilterList(
            "adguard_mobile", "AdGuard Mobile Ads",
            "https://filters.adtidy.org/extension/ublock/filters/11.txt", true,
        ),
        FilterList(
            "adguard_base", "AdGuard Base",
            "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt", false,
        ),
        FilterList(
            "peter_lowe", "Peter Lowe's Ad server list",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext", false,
        ),
        FilterList(
            "annoyances", "Fanboy's Annoyance (avisos de cookies y molestias)",
            "https://secure.fanboy.co.nz/fanboy-annoyance.txt", false,
        ),
    )

    val defaultIds: Set<String> = all.filter { it.enabledByDefault }.map { it.id }.toSet()

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
