package com.ikar.assistant.ai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free, keyless web research using the official French Wikipedia MediaWiki API.
 * This is a focused encyclopedic source, not a general-purpose web search engine.
 * Call only from Dispatchers.IO.
 */
object WikipediaResearch {
    private const val API = "https://fr.wikipedia.org/w/api.php"
    private const val MAX_RESULTS = 3

    fun search(question: String): String {
        val query = question
            .replace(Regex("(?i)recherche\\s+sur\\s+internet\\s*:?"), "")
            .replace(Regex("(?i)recherche\\s+sur\\s+le\\s+web\\s*:?"), "")
            .replace(Regex("(?i)cherche\\s+sur\\s+internet\\s*:?"), "")
            .replace(Regex("(?i)cherche\\s+sur\\s+le\\s+web\\s*:?"), "")
            .trim()
            .ifBlank { question.trim() }

        val searchUrl = "$API?action=query&list=search&srsearch=${encode(query)}&srlimit=5&format=json&utf8=1"
        val searchJson = getJson(searchUrl)
        val hits = searchJson.optJSONObject("query")?.optJSONArray("search")
            ?: throw IllegalStateException("La recherche Wikipédia n'a pas renvoyé de résultats lisibles.")

        if (hits.length() == 0) {
            return "Je n'ai trouvé aucun article Wikipédia en français pour « $query ». Essaie avec d'autres mots-clés."
        }

        val selected = (0 until minOf(hits.length(), MAX_RESULTS)).mapNotNull { index ->
            hits.optJSONObject(index)?.let { hit ->
                val pageId = hit.optLong("pageid", -1L)
                val title = hit.optString("title").trim()
                if (pageId > 0 && title.isNotBlank()) pageId to title else null
            }
        }
        if (selected.isEmpty()) {
            return "Aucun article exploitable n'a été trouvé pour « $query »."
        }

        val pageIds = selected.joinToString("|") { it.first.toString() }
        val detailsUrl = "$API?action=query&prop=extracts%7Cinfo&inprop=url&pageids=${encode(pageIds)}&exintro=1&explaintext=1&exchars=1200&format=json&formatversion=2"
        val details = getJson(detailsUrl)
        val pages = details.optJSONObject("query")?.optJSONArray("pages")
            ?: throw IllegalStateException("Les articles ont été trouvés, mais leurs contenus n'ont pas pu être lus.")

        val output = StringBuilder()
        output.append("Recherche documentaire sur Wikipédia (français) pour : « ")
            .append(query).append(" »\n\n")
        var count = 0
        for (index in 0 until pages.length()) {
            val page = pages.optJSONObject(index) ?: continue
            val title = page.optString("title").ifBlank { "Article sans titre" }
            val extract = page.optString("extract").trim()
            val url = page.optString("fullurl").ifBlank {
                "https://fr.wikipedia.org/?curid=${page.optLong("pageid")}"
            }
            if (extract.isBlank()) continue
            count++
            output.append("[$count] $title\n")
                .append(extract.take(1200)).append("\n")
                .append("Source : $url\n\n")
        }

        if (count == 0) {
            return "Les articles ont été trouvés, mais Wikipédia n'a fourni aucun résumé exploitable pour « $query »."
        }

        output.append("Limite : ces résultats proviennent uniquement de Wikipédia. Ils ne représentent pas une recherche exhaustive sur tout Internet. Vérifie les sources citées dans chaque article pour les sujets importants.")
        return output.toString().take(6500)
    }

    private fun getJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "IKARAssistant/0.50 (Android)")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("Le service Wikipédia a répondu HTTP $status. Réessaie plus tard.")
            }
            return runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("La réponse de Wikipédia est illisible.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
