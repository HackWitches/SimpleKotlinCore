
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

// Extract video URLs when directly available.
const videos = article
    ? [...article.querySelectorAll('video')]
        .flatMap(video => [
            video.currentSrc,
            video.src,
            ...[...video.querySelectorAll('source')]
                .map(source => source.src)
        ])
        .filter(url =>
            url &&
            url.startsWith('http') &&
            !url.startsWith('blob:')
        )
    : [];

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

// Download all resources and add their local paths to the JSON.
fun downloadResources(
    postJson: ObjectNode,
    resourceDirectory: Path
) {
    Files.createDirectories(resourceDirectory)

    val resources = postJson.withArray("resources")

    resources.forEachIndexed { index, resource ->
        val url = resource.path("url").asText()

        try {
            val downloadedFile = downloadFile(
                url,
                resourceDirectory,
                index + 1
            )

            (resource as ObjectNode).put(
                "localFile",
                downloadedFile.toString()
            )

            println("Downloaded: $downloadedFile")
        } catch (e: Exception) {
            (resource as ObjectNode).put(
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

fun main() = runBlocking {
    val postUrl = "https://x.com/meme_ec/status/2123051555850956930"

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

        // 1. Extract post.
        val extractedJson = extractPost{ js ->
            page.evaluate<String>(js)!!
        }

        // 2. Parse JSON into a mutable object.
        val postJson = mapper.readTree(extractedJson) as ObjectNode

        // 3. Download images and videos.
        downloadResources(postJson, resourceDirectory)

        // 4. Save final JSON, including local resource paths.
        savePostJson(postJson, jsonFile)

    } finally {
        browser.stop()
    }
}
