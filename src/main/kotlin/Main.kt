
package pl.DropDbLikeItsHard

import dev.kdriver.core.browser.createBrowser
import dev.kdriver.core.tab.evaluate
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

private val mapper = ObjectMapper()

suspend fun extractPost(
    evaluate: suspend (String) -> String
): String {
    return evaluate(
        """
        (() => {
            const meta = (selector) =>
                document.querySelector(selector)?.content || null;

            const article = document.querySelector('article');

const images = article
    ? [...article.querySelectorAll('img')]
        .map(img => img.currentSrc || img.src)
        .filter(url => url && url.includes('pbs.twimg.com/media'))
    : [];

const ogImage = meta('meta[property="og:image"]');

// Prefer the large image variant and remove duplicates.
const uniqueImages = [
    ...new Set(
        [...images, ...(ogImage ? [ogImage] : [])]
            .map(url => url.replace(/([?&])name=[^&]*/, '$1name=large'))
            .map(url => url.replace(/[?&]$/, ''))
            .map(url => url.split('?')[0] + '?format=webp&name=large')
    )
];

// Find video URLs exposed by the video element.
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

// Download an individual image/video.
fun downloadFile(
    url: String,
    destination: Path,
    index: Int
): Path {
    val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", "Mozilla/5.0")
        .header("Referer", "https://x.com/")
        .timeout(Duration.ofSeconds(60))
        .GET()
        .build()

    val response = client.send(
        request,
        HttpResponse.BodyHandlers.ofByteArray()
    )

    if (response.statusCode() !in 200..299) {
        throw RuntimeException("HTTP ${response.statusCode()}")
    }

    // Try to get the extension from the URL.
    val uri = URI.create(url)
    var extension = uri.path.substringAfterLast('.', "")

    if (extension.isBlank() || extension.length > 5) {
        extension = uri.rawQuery
            ?.split("&")
            ?.firstOrNull { it.startsWith("format=") }
            ?.substringAfter("=")
            ?: "bin"
    }

    val filename = "resource-$index.$extension"
    val file = destination.resolve(filename)

    Files.write(file, response.body())

    return file
}

fun downloadHlsVideo(
    url: String,
    destination: Path,
    index: Int
): Path {
    val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    fun getBytes(uri: URI): ByteArray {
        val request = HttpRequest.newBuilder()
            .uri(uri)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://x.com/")
            .header("Origin", "https://x.com")
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build()

        val response = client.send(
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

    fun getText(uri: URI): String {
        return getBytes(uri).toString(Charsets.UTF_8)
    }

    // Resolve a master playlist to a media playlist.
    fun resolveMediaPlaylist(startUri: URI): Pair<URI, String> {
        var currentUri = startUri

        repeat(5) {
            val playlist = getText(currentUri)

            val lines = playlist.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            // A master playlist contains EXT-X-STREAM-INF.
            val variantIndex = lines.indexOfFirst {
                it.startsWith("#EXT-X-STREAM-INF:")
            }

            if (variantIndex == -1) {
                // This is already a media playlist.
                return currentUri to playlist
            }

            val variantPath = lines
                .drop(variantIndex + 1)
                .firstOrNull { !it.startsWith("#") }
                ?: throw RuntimeException(
                    "Master playlist has no media playlist URL"
                )

            currentUri = currentUri.resolve(variantPath)
        }

        throw RuntimeException("Too many nested HLS playlists")
    }

    // Download the actual media playlist.
    val (mediaUri, playlist) =
        resolveMediaPlaylist(URI.create(url))

    // Find the initialization segment.
    val mapLine = playlist.lines()
        .firstOrNull { it.startsWith("#EXT-X-MAP:") }
        ?: throw RuntimeException(
            "No EXT-X-MAP found. This downloader expects fMP4 HLS."
        )

    val mapUrl = Regex("""URI="([^"]+)"""")
        .find(mapLine)
        ?.groupValues
        ?.get(1)
        ?: throw RuntimeException("Could not parse EXT-X-MAP URI")

    val initializationUri = mediaUri.resolve(mapUrl)

    // Find all media fragments in playlist order.
    val segments = playlist.lines()
        .map { it.trim() }
        .filter {
            it.isNotEmpty() &&
                    !it.startsWith("#")
        }
        .map { mediaUri.resolve(it) }

    if (segments.isEmpty()) {
        throw RuntimeException("No video segments found")
    }

    val outputFile = destination.resolve("resource-$index.mp4")

    // Write initialization segment followed by all media fragments.
    Files.newOutputStream(outputFile).use { output ->
        println("Downloading video initialization segment...")

        output.write(getBytes(initializationUri))

        segments.forEachIndexed { segmentIndex, segmentUri ->
            println(
                "Downloading video segment " +
                        "${segmentIndex + 1}/${segments.size}"
            )

            output.write(getBytes(segmentUri))
        }
    }

    val size = Files.size(outputFile)

    if (size == 0L) {
        Files.deleteIfExists(outputFile)
        throw RuntimeException("Downloaded video is empty")
    }

    println("Video downloaded: $outputFile ($size bytes)")

    return outputFile
}

// Download all resources and add their local paths to the JSON.
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
                        URI.create(url).path.endsWith(".m3u8", ignoreCase = true) -> {

                    downloadHlsVideo(
                        url,
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

// Save formatted JSON to disk.
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

suspend fun activateVideo(
    evaluate: suspend (String) -> String
) {
    evaluate(
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

fun main() = runBlocking {
    val postUrl = "https://x.com/meme_ec/status/2123060328691097747"

    val statusId = Regex("/status/(\\d+)")
        .find(postUrl)
        ?.groupValues
        ?.get(1)
        ?: error("Could not extract X post ID")

    val uri = URI.create(postUrl)

    val website = uri.host
        .removePrefix("www.")
        .replace(".", "_")

    val postDirectory = Path.of("output", website, statusId)
    val jsonFile = postDirectory.resolve("xcom-status-$statusId.json")
    val resourceDirectory = postDirectory.resolve("resources")

    Files.createDirectories(postDirectory)
    Files.createDirectories(resourceDirectory)

    val browser = createBrowser(this)

    try {
        val page = browser.get(postUrl)

        page.wait(5000)

        // 1. Activate video playback.
        val activationResult = activateVideo { js ->
            page.evaluate<String>(js)!!
        }

        println(activationResult)

        // Give X time to request video data.
        page.wait(5000)

        // 2. Extract post and resources.
        val extractedJson = extractPost { js ->
            page.evaluate<String>(js)!!
        }

        // 3. Parse JSON into a mutable object.
        val postJson = mapper.readTree(extractedJson) as ObjectNode

        // 4. Download images and videos.
        downloadResources(postJson, resourceDirectory)

        // 5. Save final JSON, including local resource paths.
        savePostJson(postJson, jsonFile)

    } finally {
        browser.stop()
    }
}
