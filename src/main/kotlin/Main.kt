
package pl.DropDbLikeItsHard

import dev.kdriver.core.browser.createBrowser
import dev.kdriver.core.tab.evaluate
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val mapper = ObjectMapper()

private val httpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(20))
    .build()

// -----------------------------------------------------------------------------
// Browser extraction
// -----------------------------------------------------------------------------

suspend fun extractPost(
    evaluate: suspend (String) -> String
): String {
    return evaluate(
        """
        (() => {
            const meta = selector =>
                document.querySelector(selector)?.content || null;

            const article = document.querySelector('article');

            const images = article
                ? [...article.querySelectorAll('img')]
                    .map(img => img.currentSrc || img.src)
                    .filter(url =>
                        url && url.includes('pbs.twimg.com/media')
                    )
                : [];

            const ogImage = meta('meta[property="og:image"]');

            const uniqueImages = [
                ...new Set(
                    [...images, ...(ogImage ? [ogImage] : [])]
                        .map(url =>
                            url.replace(/([?&])name=[^&]*/, '$1name=large')
                        )
                        .map(url =>
                            url.replace(/[?&]$/, '')
                        )
                        .map(url =>
                            url.split('?')[0] + '?format=webp&name=large'
                        )
                )
            ];

            const elementVideos = article
                ? [...article.querySelectorAll('video')]
                    .flatMap(video => [
                        video.currentSrc,
                        video.src,
                        ...[...video.querySelectorAll('source')]
                            .map(source => source.src)
                    ])
                : [];

            const networkVideos = performance
                .getEntriesByType('resource')
                .map(entry => entry.name)
                .filter(url =>
                    url.includes('video.twimg.com') &&
                    /\.m3u8(\?|$)/i.test(url)
                );

            const hlsPlaylists = [...new Set(networkVideos)];

            const masterPlaylist = hlsPlaylists.find(url =>
                new URL(url).pathname.includes('/pl/')
            );

            const progressiveVideos = [...new Set(elementVideos)]
                .filter(url =>
                    url &&
                    /^https?:\/\//i.test(url) &&
                    /\.mp4(\?|$)/i.test(url)
                );

            const videos = masterPlaylist
                ? [masterPlaylist]
                : hlsPlaylists.length > 0
                    ? [hlsPlaylists[0]]
                    : progressiveVideos.slice(0, 1);

            const resources = [
                ...uniqueImages.map(url => ({
                    type: 'image',
                    url: url
                })),
                ...[...new Set(videos)].map(url => ({
                    type: 'video',
                    url: url
                }))
            ];

            return JSON.stringify({
                title: document.title,
                postTitle: meta('meta[property="og:title"]'),
                text: meta('meta[property="og:description"]'),
                date: meta('meta[property="article:published_time"]'),
                authorUrl: meta('meta[property="article:author"]'),
                postUrl: meta('meta[property="og:url"]'),
                images: uniqueImages,
                resources: resources
            }, null, 2);
        })()
        """.trimIndent()
    )
}

suspend fun activateVideo(
    evaluate: suspend (String) -> String
): String {
    return evaluate(
        """
        (() => {
            const article = document.querySelector('article');
            const video = article?.querySelector('video');

            if (video) {
                video.muted = true;
                video.play().catch(() => {});
                return 'Video playback requested';
            }

            const playButton = article
                ? [...article.querySelectorAll('button')]
                    .find(button =>
                        /play/i.test(button.getAttribute('aria-label') || '')
                    )
                : null;

            if (playButton) {
                playButton.click();
                return 'Play button clicked';
            }

            return 'No video player found';
        })()
        """.trimIndent()
    )
}

// -----------------------------------------------------------------------------
// HTTP helpers
// -----------------------------------------------------------------------------

fun getBytes(uri: URI): ByteArray {
    val request = HttpRequest.newBuilder()
        .uri(uri)
        .header("User-Agent", "Mozilla/5.0")
        .header("Referer", "https://x.com/")
        .header("Origin", "https://x.com")
        .header("Accept", "*/*")
        .timeout(Duration.ofMinutes(2))
        .GET()
        .build()

    val response = httpClient.send(
        request,
        HttpResponse.BodyHandlers.ofByteArray()
    )

    if (response.statusCode() !in 200..299) {
        throw RuntimeException(
            "HTTP ${response.statusCode()} downloading $uri"
        )
    }

    return response.body()
}

fun getText(uri: URI): String =
    getBytes(uri).toString(Charsets.UTF_8)

fun saveBytes(uri: URI, destination: Path): Path {
    Files.createDirectories(destination.parent)
    Files.write(destination, getBytes(uri))
    return destination
}

// -----------------------------------------------------------------------------
// HLS master playlist parsing
// -----------------------------------------------------------------------------

data class HlsVariant(
    val bandwidth: Long,
    val audioGroup: String?,
    val uri: URI
)

data class HlsAudioRendition(
    val groupId: String,
    val uri: URI,
    val isDefault: Boolean,
    val isAutoselect: Boolean
)

data class HlsMaster(
    val variants: List<HlsVariant>,
    val audioRenditions: List<HlsAudioRendition>
)

fun parseAttributes(line: String): Map<String, String> {
    val result = mutableMapOf<String, String>()

    val regex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    regex.findAll(line.substringAfter(':')).forEach { match ->
        result[match.groupValues[1]] =
            match.groupValues[2].trim('"')
    }

    return result
}

fun parseMasterPlaylist(
    masterUri: URI,
    content: String
): HlsMaster {
    val lines = content.lines().map { it.trim() }

    val variants = mutableListOf<HlsVariant>()
    val audio = mutableListOf<HlsAudioRendition>()

    lines.forEachIndexed { index, line ->

        // Audio playlists.
        if (line.startsWith("#EXT-X-MEDIA:")) {
            val attributes = parseAttributes(line)

            if (attributes["TYPE"] == "AUDIO") {
                val audioUri = attributes["URI"]

                if (audioUri != null) {
                    audio += HlsAudioRendition(
                        groupId = attributes["GROUP-ID"] ?: "",
                        uri = masterUri.resolve(audioUri),
                        isDefault = attributes["DEFAULT"] == "YES",
                        isAutoselect = attributes["AUTOSELECT"] == "YES"
                    )
                }
            }
        }

        // Video variants.
        if (line.startsWith("#EXT-X-STREAM-INF:")) {
            val attributes = parseAttributes(line)

            val variantPath = lines
                .drop(index + 1)
                .firstOrNull {
                    it.isNotEmpty() && !it.startsWith("#")
                }
                ?: return@forEachIndexed

            val bandwidth = (
                    attributes["BANDWIDTH"]
                        ?: attributes["AVERAGE-BANDWIDTH"]
                        ?: "0"
                    ).toLongOrNull() ?: 0L

            variants += HlsVariant(
                bandwidth = bandwidth,
                audioGroup = attributes["AUDIO"],
                uri = masterUri.resolve(variantPath)
            )
        }
    }

    if (variants.isEmpty()) {
        throw RuntimeException("No video variants found in master playlist")
    }

    return HlsMaster(
        variants = variants,
        audioRenditions = audio
    )
}

// -----------------------------------------------------------------------------
// Save all playlists referenced by the master playlist
// -----------------------------------------------------------------------------

fun saveAllPlaylists(
    masterUri: URI,
    masterContent: String,
    master: HlsMaster,
    directory: Path
) {
    Files.createDirectories(directory)

    Files.writeString(
        directory.resolve("master.m3u8"),
        masterContent
    )

    val playlistUris = linkedSetOf<URI>()

    master.variants.forEach {
        playlistUris += it.uri
    }

    master.audioRenditions.forEach {
        playlistUris += it.uri
    }

    playlistUris.forEachIndexed { index, uri ->
        val content = getText(uri)

        val originalName = uri.path.substringAfterLast('/')
            .ifBlank { "playlist.m3u8" }

        val filename = "%02d-%s".format(index + 1, originalName)

        Files.writeString(
            directory.resolve(filename),
            content
        )

        println("Saved playlist: $filename")
    }
}

// -----------------------------------------------------------------------------
// HLS media playlist parsing
// -----------------------------------------------------------------------------

data class HlsMediaPlaylist(
    val initializationUri: URI,
    val segments: List<URI>
)

fun parseMediaPlaylist(
    playlistUri: URI,
    content: String
): HlsMediaPlaylist {
    val lines = content.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val mapLine = lines.firstOrNull {
        it.startsWith("#EXT-X-MAP:")
    } ?: throw RuntimeException(
        "No EXT-X-MAP found in $playlistUri"
    )

    val initializationPath = Regex("""URI="([^"]+)"""")
        .find(mapLine)
        ?.groupValues
        ?.get(1)
        ?: throw RuntimeException(
            "Could not parse initialization segment URI"
        )

    val segments = lines
        .filter { !it.startsWith("#") }
        .map { playlistUri.resolve(it) }

    if (segments.isEmpty()) {
        throw RuntimeException("No media segments found in $playlistUri")
    }

    return HlsMediaPlaylist(
        initializationUri = playlistUri.resolve(initializationPath),
        segments = segments
    )
}

// -----------------------------------------------------------------------------
// Download and concatenate one HLS track
// -----------------------------------------------------------------------------

fun downloadHlsTrack(
    playlistUri: URI,
    outputFile: Path,
    trackName: String
): Path {
    println("Reading $trackName playlist...")

    val playlistContent = getText(playlistUri)

    val playlist = parseMediaPlaylist(
        playlistUri,
        playlistContent
    )

    Files.createDirectories(outputFile.parent)

    Files.newOutputStream(outputFile).use { output ->

        println("Downloading $trackName initialization segment...")

        output.write(getBytes(playlist.initializationUri))

        playlist.segments.forEachIndexed { index, segmentUri ->

            println(
                "Downloading $trackName segment " +
                        "${index + 1}/${playlist.segments.size}"
            )

            output.write(getBytes(segmentUri))
        }
    }

    val size = Files.size(outputFile)

    if (size == 0L) {
        throw RuntimeException("$trackName track is empty")
    }

    println(
        "$trackName track downloaded: $outputFile ($size bytes)"
    )

    return outputFile
}

// -----------------------------------------------------------------------------
// FFmpeg muxing
// -----------------------------------------------------------------------------

fun muxTracks(
    videoFile: Path,
    audioFile: Path?,
    outputFile: Path
): Path {
    Files.createDirectories(outputFile.parent)

    val command = mutableListOf(
        "ffmpeg",
        "-hide_banner",
        "-y",
        "-i", videoFile.toAbsolutePath().toString()
    )

    if (audioFile != null) {
        command += listOf(
            "-i", audioFile.toAbsolutePath().toString()
        )
    }

    command += listOf(
        "-map", "0:v:0"
    )

    if (audioFile != null) {
        command += listOf(
            "-map", "1:a:0"
        )
    } else {
        command += listOf(
            "-map", "0:a:0?"
        )
    }

    command += listOf(
        "-c", "copy",
        "-movflags", "+faststart",
        outputFile.toAbsolutePath().toString()
    )

    println("Muxing audio and video...")

    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()

    process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { println(it) }
    }

    val exitCode = process.waitFor()

    if (exitCode != 0) {
        throw RuntimeException(
            "FFmpeg failed with exit code $exitCode"
        )
    }

    if (!Files.exists(outputFile) || Files.size(outputFile) == 0L) {
        throw RuntimeException("FFmpeg did not produce an output file")
    }

    println("Final MP4 created: $outputFile")

    return outputFile
}

// -----------------------------------------------------------------------------
// Complete HLS download
// -----------------------------------------------------------------------------

fun downloadHlsVideo(
    masterUri: URI,
    destination: Path,
    index: Int
): Path {
    val hlsDirectory = destination.resolve("hls-$index")
    val playlistDirectory = hlsDirectory.resolve("playlists")
    val trackDirectory = hlsDirectory.resolve("tracks")

    Files.createDirectories(playlistDirectory)
    Files.createDirectories(trackDirectory)

    println("Downloading HLS master playlist...")

    val masterContent = getText(masterUri)

    val master = parseMasterPlaylist(
        masterUri,
        masterContent
    )

    // Save every playlist referenced by the master.
    saveAllPlaylists(
        masterUri,
        masterContent,
        master,
        playlistDirectory
    )

    // Select the highest-bandwidth video variant.
    val bestVideo = master.variants.maxBy {
        it.bandwidth
    }

    println(
        "Selected video: ${bestVideo.uri} " +
                "(${bestVideo.bandwidth} bps)"
    )

    // Find the audio playlist belonging to that video variant.
    val bestAudio = bestVideo.audioGroup?.let { group ->
        master.audioRenditions
            .filter { it.groupId == group }
            .sortedWith(
                compareByDescending<HlsAudioRendition> {
                    it.isDefault
                }.thenByDescending {
                    it.isAutoselect
                }
            )
            .firstOrNull()
    }

    if (bestVideo.audioGroup != null && bestAudio == null) {
        throw RuntimeException(
            "Could not find audio playlist for group " +
                    bestVideo.audioGroup
        )
    }

    println("Selected audio: ${bestAudio?.uri ?: "not separate"}")

    // Download video track.
    val videoTrack = downloadHlsTrack(
        playlistUri = bestVideo.uri,
        outputFile = trackDirectory.resolve("video-track.mp4"),
        trackName = "Video"
    )

    // Download audio track, if the master playlist provides one.
    val audioTrack = bestAudio?.let {
        downloadHlsTrack(
            playlistUri = it.uri,
            outputFile = trackDirectory.resolve("audio-track.mp4"),
            trackName = "Audio"
        )
    }

    // Mux without re-encoding.
    val outputFile = destination.resolve("resource-$index.mp4")

    return muxTracks(
        videoFile = videoTrack,
        audioFile = audioTrack,
        outputFile = outputFile
    )
}

// -----------------------------------------------------------------------------
// Ordinary resource downloads
// -----------------------------------------------------------------------------

fun downloadFile(
    url: String,
    destination: Path,
    index: Int
): Path {
    val uri = URI.create(url)

    var extension = uri.path.substringAfterLast('.', "")

    if (extension.isBlank() || extension.length > 5) {
        extension = uri.rawQuery
            ?.split("&")
            ?.firstOrNull { it.startsWith("format=") }
            ?.substringAfter("=")
            ?: "bin"
    }

    val outputFile = destination.resolve(
        "resource-$index.$extension"
    )

    Files.createDirectories(destination)

    Files.write(outputFile, getBytes(uri))

    return outputFile
}

fun downloadResources(
    postJson: ObjectNode,
    resourceDirectory: Path
) {
    Files.createDirectories(resourceDirectory)

    val resources = postJson.withArray("resources")

    resources.forEachIndexed { index, resource ->
        val url = resource.path("url").asText()
        val resourceObject = resource as ObjectNode

        try {
            val downloadedFile = when {
                resource.path("type").asText() == "video" &&
                        URI.create(url).path.endsWith(
                            ".m3u8",
                            ignoreCase = true
                        ) -> {
                    downloadHlsVideo(
                        URI.create(url),
                        resourceDirectory,
                        index + 1
                    )
                }

                else -> {
                    downloadFile(
                        url,
                        resourceDirectory,
                        index + 1
                    )
                }
            }

            resourceObject.put(
                "localFile",
                downloadedFile.toString()
            )

            resourceObject.put(
                "fileSizeBytes",
                Files.size(downloadedFile)
            )

            println("Downloaded: $downloadedFile")

        } catch (e: Exception) {
            resourceObject.put(
                "downloadError",
                e.message ?: "Unknown error"
            )

            println("Failed to download: $url")
            println("Reason: ${e.message}")
        }
    }
}

// -----------------------------------------------------------------------------
// JSON output
// -----------------------------------------------------------------------------

fun savePostJson(
    postJson: ObjectNode,
    outputFile: Path
) {
    Files.writeString(
        outputFile,
        mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(postJson)
    )

    println("JSON saved: $outputFile")
}


// -----------------------------------------------------------------------------
// Output filename generation
// -----------------------------------------------------------------------------

fun sanitizeFilenamePart(value: String): String {
    return value
        .trim()
        .replace(Regex("""[\u0000-\u001F<>:"/\\|?*]"""), "_")
        .replace(Regex("""\s+"""), "_")
        .replace(Regex("""_+"""), "_")
        .trim('.', '_', ' ')
        .take(120)
}

// -----------------------------------------------------------------------------
// Filename-generation functions
// -----------------------------------------------------------------------------


fun buildPostFilename(
    postJson: ObjectNode,
    statusId: String
): String {
    // Extract author username from authorUrl.
    val authorUrl = postJson.path("authorUrl").asText("")

    val author = runCatching {
        URI.create(authorUrl)
            .path
            .trim('/')
            .substringAfterLast('/')
            .removePrefix("@")
    }.getOrDefault("unknown")

    // Try all available title/text fields.
    val candidates = listOf(
        postJson.path("postTitle").asText(""),
        postJson.path("title").asText(""),
        postJson.path("text").asText("")
    ).filter { it.isNotBlank() }

    // Polish, English curly and ordinary quotation marks.
    val quotationRegex = Regex(
        """„([^”]+)”|“([^”]+)”|"([^"]+)"|'([^']+)'"""
    )

    val quotedText = candidates.firstNotNullOfOrNull { candidate ->
        quotationRegex.find(candidate)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
    }

    // If no quotation marks exist, use the actual post text.
    val filenameText = quotedText
        ?: postJson.path("text").asText("").ifBlank {
            candidates.firstOrNull() ?: statusId
        }

    val safeAuthor = sanitizeFilenamePart(author)
        .ifBlank { "unknown" }

    val safeTitle = sanitizeFilenamePart(
        filenameText
            .trim()
            .replace(Regex("""[.!?,;:…]+$"""), "")
    ).ifBlank { statusId }

    return "xcom_${safeAuthor}_${safeTitle}.json"
}


// -----------------------------------------------------------------------------
// Browser readiness
// -----------------------------------------------------------------------------

suspend fun waitForPageReady(
    evaluate: suspend (String) -> String,
    timeoutMs: Long = 30_000
) {
    val started = System.currentTimeMillis()

    val script = """
        (() => {
            const article = document.querySelector('article');
            const title = document.querySelector(
                'meta[property="og:title"]'
            )?.content;

            return article && title ? 'READY' : 'WAIT';
        })()
    """.trimIndent()

    while (System.currentTimeMillis() - started < timeoutMs) {
        if (evaluate(script) == "READY") {
            println("Post page is ready.")
            return
        }

        delay(500.milliseconds)
    }

    throw RuntimeException(
        "Post page did not become ready within ${timeoutMs}ms"
    )
}

suspend fun waitForVideoResources(
    evaluate: suspend (String) -> String,
    timeoutMs: Long = 20_000
) {
    val started = System.currentTimeMillis()

    val script = """
        (() => {
            const article = document.querySelector('article');
            const video = article?.querySelector('video');

            if (!video) {
                return 'READY';
            }

            const resources = performance
                .getEntriesByType('resource')
                .map(entry => entry.name);

            const hasHls = resources.some(url =>
                url.includes('video.twimg.com') &&
                /\.m3u8(\?|$)/i.test(url)
            );

            const hasMp4 =
                /\.mp4(\?|$)/i.test(video.currentSrc || '') ||
                [...video.querySelectorAll('source')]
                    .some(source => /\.mp4(\?|$)/i.test(source.src));

            return hasHls || hasMp4 ? 'READY' : 'WAIT';
        })()
    """.trimIndent()

    while (System.currentTimeMillis() - started < timeoutMs) {
        if (evaluate(script) == "READY") {
            println("Video resources are ready.")
            return
        }

        delay(500)
    }

    println(
        "Warning: video resource detection timed out. " +
                "Continuing with the available resources."
    )
}



fun main() = runBlocking {

    // The post URL is defined only once.
    val postUrl =
        "https://x.com/hackathons/status/903672606733340674"

    val statusId = Regex("/status/(\\d+)")
        .find(postUrl)
        ?.groupValues
        ?.get(1)
        ?: error("Could not extract X post ID")

    val uri = URI.create(postUrl)

    val website = uri.host
        .removePrefix("www.")
        .replace(".", "")

    val postDirectory = Path.of(
        "output",
        website,
        statusId
    )

    val resourceDirectory = postDirectory.resolve("resources")

    Files.createDirectories(postDirectory)
    Files.createDirectories(resourceDirectory)

    val browser = createBrowser(this)

    try {
        // ---------------------------------------------------------------------
        // 1. Open the post.
        // ---------------------------------------------------------------------

        val page = browser.get(postUrl)

        waitForPageReady(
            evaluate = { js ->
                page.evaluate<String>(js)!!
            }
        )

        // ---------------------------------------------------------------------
        // 2. Activate the video and wait for its playlist requests.
        // ---------------------------------------------------------------------

        val activationResult = activateVideo { js ->
            page.evaluate<String>(js)!!
        }

        println(activationResult)

        waitForVideoResources(
            evaluate = { js ->
                page.evaluate<String>(js)!!
            }
        )

        // ---------------------------------------------------------------------
        // 3. Extract post metadata and resource URLs.
        // ---------------------------------------------------------------------

        val extractedJson = extractPost { js ->
            page.evaluate<String>(js)!!
        }

        val postJson = mapper.readTree(extractedJson) as ObjectNode

        // ---------------------------------------------------------------------
        // 4. Generate the JSON filename from author and quoted post title.
        // ---------------------------------------------------------------------

        /// debug prints
        println("Post title: ${postJson.path("postTitle").asText()}")
        println("Document title: ${postJson.path("title").asText()}")
        println("Post text: ${postJson.path("text").asText()}")
        println("Author URL: ${postJson.path("authorUrl").asText()}")

        val jsonFilename = buildPostFilename(
            postJson,
            statusId
        )

        val jsonFile = postDirectory.resolve(jsonFilename)

        println("Output JSON: $jsonFile")

        // ---------------------------------------------------------------------
        // 5. Download resources while the browser remains open.
        // ---------------------------------------------------------------------

        downloadResources(
            postJson,
            resourceDirectory
        )

        // ---------------------------------------------------------------------
        // 6. Save JSON with the downloaded file paths.
        // ---------------------------------------------------------------------

        savePostJson(
            postJson,
            jsonFile
        )

    } finally {
        // Browser closes only after downloads and JSON saving finish.
        browser.stop()
        println("Browser closed.")
    }
}

