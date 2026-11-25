// Use an integer for version numbers
version = 4

cloudstream {
    description = "Películas y series en Castellano, Latino y VOSE desde AresHD."
    authors = listOf("Kodi-Cloudstream")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    requiresResources = false
    language = "es"

    iconUrl = "https://areshd.com/favicon.ico"
}
