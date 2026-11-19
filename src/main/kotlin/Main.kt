
package pl.DropDbLikeItsHard

import dev.kdriver.core.browser.createBrowser
import dev.kdriver.core.tab.evaluate
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {

    val browser = createBrowser(this)

    try {
        val page = browser.get(
            "https://x.com/meme_ec/status/2123051555850956930"
        )

        page.wait(5000)

        val result = page.evaluate<String>(
            """
            (() => {

                // Helper for Open Graph metadata
                const meta = (selector) =>
                    document.querySelector(selector)?.content || null;

                // Find the actual post article
                const article = document.querySelector('article');

                // Extract images from metadata and rendered HTML
                const images = [
                    ...new Set([
                        meta('meta[property="og:image"]'),
                        ...(article ? [
                            ...article.querySelectorAll('img')
                        ].map(img => img.currentSrc || img.src) : [])
                    ].filter(Boolean))
                ];

                // Extract videos
                const videos = article
                    ? [...article.querySelectorAll('video')].map(video => ({
                        url: video.currentSrc || video.src || null,
                        poster: video.poster || null,
                        sources: [
                            ...video.querySelectorAll('source')
                        ].map(source => source.src)
                    }))
                    : [];

                // Extract links
                const links = article
                    ? [...article.querySelectorAll('a[href]')]
                        .map(a => ({
                            text: a.innerText.trim(),
                            url: a.href
                        }))
                        .filter(a => a.text && a.url.startsWith('http'))
                    : [];

                // Extract hashtags
                const hashtags = article
                    ? [...article.querySelectorAll('a[href*="/hashtag/"]')]
                        .map(a => a.innerText.trim())
                    : [];

                // Author
                const authorUrl = meta(
                    'meta[property="article:author"]'
                );

                const authorElement = article?.querySelector(
                    'a[href^="/"] div'
                );

                const author = authorElement?.innerText || null;

                return JSON.stringify({

                    title:
                        document.title,

                    postTitle:
                        meta('meta[property="og:title"]'),

                    text:
                        meta('meta[property="og:description"]'),

                    date:
                        meta('meta[property="article:published_time"]'),

                    author:
                        author,

                    authorUrl:
                        authorUrl,

                    postUrl:
                        meta('meta[property="og:url"]'),

                    images:
                        images,

                    videos:
                        videos,

                    links:
                        links,

                    hashtags:
                        hashtags

                });

            })()
            """.trimIndent()
        )

        println(result)


    } finally {
        browser.stop()
    }
}
