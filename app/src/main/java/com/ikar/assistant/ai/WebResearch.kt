package com.ikar.assistant.ai

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Multi-source public web research. No API key is required.
 * Sources are queried independently so one unavailable provider does not erase other results.
 * Call from Dispatchers.IO only.
 */
object WebResearch {
    private const val USER_AGENT = "IKARAssistant/0.51 (Android; public research client)"
    private const val CONNECT_TIMEOUT_MS = 9_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_OUTPUT_CHARS = 8_000

    internal fun cleanQuery(question: String): String {
        val cleaned = question
            .replace(Regex("(?i)recherche\\s+sur\\s+internet\\s*:?"), "")
            .replace(Regex("(?i)cherche\\s+sur\\s+internet\\s*:?"), "")
            .replace(Regex("(?i)recherche\\s+sur\\s+le\\s+web\\s*:?"), "")
            .replace(Regex("(?i)cherche\\s+sur\\s+le\\s+web\\s*:?"), "")
            .replace(Regex("(?i)fais\\s+une\\s+recherche\\s+web\\s*:?"), "")
            .replace(Regex("(?i)recherche\\s+web\\s*:?"), "")
            .replace(Regex("(?i)trouve\\s+des\\s+sources\\s+sur\\s+internet\\s*:?"), "")
            .trim()
            .trim(':', '-', ' ', '?')
        return cleaned.ifBlank { question.trim() }
    }

    fun search(question: String): String {
        val query = cleanQuery(question)
        require(query.isNotBlank()) { "Précise le sujet de ta recherche." }

        val executor = Executors.newFixedThreadPool(3)
        val tasks = listOf(
            Callable { ProviderResult("Wikipédia", runCatching { searchWikipedia(query) }) },
            Callable { ProviderResult("Actualités Google News", runCatching { searchNews(query) }) },
            Callable { ProviderResult("OpenAlex (recherche scientifique)", runCatching { searchOpenAlex(query) }) }
        )

        val results = try {
            executor.invokeAll(tasks, 24, TimeUnit.SECONDS).mapNotNull { future ->
                if (future.isCancelled) null else runCatching { future.get() }.getOrNull()
            }
        } finally {
            executor.shutdownNow()
        }

        val successfulProviders = results.filter { it.result.isSuccess }
        if (successfulProviders.isEmpty()) {
            val details = results.mapNotNull { it.result.exceptionOrNull()?.message }.distinct().take(2)
            throw IllegalStateException(
                "Les trois sources de recherche sont momentanément indisponibles." +
                    if (details.isNotEmpty()) " " + details.joinToString(" ") else " Vérifie ta connexion puis réessaie."
            )
        }

        val output = StringBuilder()
        output.append("Recherche Internet multi-sources pour : « ").append(query).append(" »\n")
        output.append("IKAR interroge séparément des sources encyclopédiques, d'actualité et scientifiques.\n\n")

        var itemCount = 0
        successfulProviders.forEach { provider ->
            output.append("=== ").append(provider.name).append(" ===\n")
            val items = provider.result.getOrDefault(emptyList())
            if (items.isEmpty()) {
                output.append("Aucun résultat exploitable pour cette source.\n\n")
            } else {
                items.forEachIndexed { index, item ->
                    itemCount++
                    output.append(index + 1).append(". ").append(item).append("\n\n")
                }
            }
        }

        val failures = results.filter { it.result.isFailure }
        if (failures.isNotEmpty()) {
            output.append("Sources temporairement indisponibles : ")
                .append(failures.joinToString { it.name }).append(".\n")
        }
        if (itemCount == 0) {
            output.append("Aucun résultat détaillé n'a été retourné. Reformule le sujet avec des mots-clés plus précis.\n")
        }
        output.append(
            "\nÀ savoir : cette recherche combine les résultats accessibles de ces trois services, pas l'intégralité du Web. " +
                "Les actualités peuvent contenir des liens vers des médias externes ; les notices OpenAlex sont des références scientifiques, " +
                "pas nécessairement des articles en texte intégral. Vérifie les dates, les auteurs et les sources avant de prendre une décision."
        )
        return output.toString().take(MAX_OUTPUT_CHARS)
    }

    private data class ProviderResult(
        val name: String,
        val result: Result<List<String>>
    )

    private fun searchWikipedia(query: String): List<String> {
        val searchUrl = "https://fr.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encode(query)}&srlimit=3&format=json&utf8=1"
        val searchJson = JSONObject(getText(searchUrl))
        val hits = searchJson.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        if (hits.length() == 0) return emptyList()

        val ids = (0 until hits.length()).mapNotNull { index ->
            hits.optJSONObject(index)?.optLong("pageid", -1L)?.takeIf { it > 0L }
        }
        if (ids.isEmpty()) return emptyList()

        val detailUrl = "https://fr.wikipedia.org/w/api.php?action=query&prop=extracts%7Cinfo&inprop=url&pageids=${encode(ids.joinToString("|"))}&exintro=1&explaintext=1&exchars=900&format=json&formatversion=2"
        val pages = JSONObject(getText(detailUrl)).optJSONObject("query")?.optJSONArray("pages")
            ?: return emptyList()

        return (0 until pages.length()).mapNotNull { index ->
            val page = pages.optJSONObject(index) ?: return@mapNotNull null
            val title = page.optString("title").trim()
            val extract = page.optString("extract").trim()
            val link = page.optString("fullurl").ifBlank {
                "https://fr.wikipedia.org/?curid=${page.optLong("pageid")}"
            }
            if (title.isBlank() || extract.isBlank()) null
            else "$title\n$extract\nSource : $link"
        }
    }

    private fun searchNews(query: String): List<String> {
        val address = "https://news.google.com/rss/search?q=${encode(query)}&hl=fr&gl=TG&ceid=TG:fr"
        val xml = getText(address)
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(StringReader(xml))

        val items = mutableListOf<String>()
        var inItem = false
        var title = ""
        var link = ""
        var date = ""
        var source = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && items.size < 5) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = ""
                        link = ""
                        date = ""
                        source = ""
                    }
                    "title" -> if (inItem) title = parser.nextText().trim()
                    "link" -> if (inItem) link = parser.nextText().trim()
                    "pubDate" -> if (inItem) date = parser.nextText().trim()
                    "source" -> if (inItem) source = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name == "item" && inItem) {
                    if (title.isNotBlank() && link.startsWith("https://")) {
                        val attribution = listOf(source, date).filter { it.isNotBlank() }.joinToString(" · ")
                        items.add("$title" + if (attribution.isNotBlank()) "\n$attribution" else "" + "\nSource : $link")
                    }
                    inItem = false
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun searchOpenAlex(query: String): List<String> {
        val address = "https://api.openalex.org/works?search=${encode(query)}&per-page=3&select=display_name,publication_year,doi,primary_location,authorships"
        val works = JSONObject(getText(address)).optJSONArray("results") ?: return emptyList()
        return (0 until works.length()).mapNotNull { index ->
            val work = works.optJSONObject(index) ?: return@mapNotNull null
            val title = work.optString("display_name").trim()
            if (title.isBlank()) return@mapNotNull null
            val year = work.optInt("publication_year", 0).takeIf { it > 0 }
            val authorships = work.optJSONArray("authorships")
            val authors = (0 until minOf(authorships?.length() ?: 0, 3)).mapNotNull { authorIndex ->
                authorships?.optJSONObject(authorIndex)?.optJSONObject("author")?.optString("display_name")
                    ?.takeIf { !it.isNullOrBlank() }
            }
            val location = work.optJSONObject("primary_location")
            val landingPage = location?.optString("landing_page_url").orEmpty()
            val doi = work.optString("doi").takeIf { it.startsWith("http") }
            val link = landingPage.ifBlank { doi.orEmpty() }.ifBlank { work.optString("id") }
            val metadata = listOfNotNull(year?.toString(), authors.takeIf { it.isNotEmpty() }?.joinToString(", "))
                .joinToString(" · ")
            "$title" + if (metadata.isNotBlank()) "\n$metadata" else "" + if (link.isNotBlank()) "\nRéférence : $link" else ""
        }
    }

    private fun getText(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json, application/rss+xml, application/xml, text/xml")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status")
            }
            if (body.isBlank()) throw IllegalStateException("Réponse vide")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
