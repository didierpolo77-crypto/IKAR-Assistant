package com.ikar.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class IkarOrchestratorTest {
    @Test
    fun explicitMemoryCommandAlwaysStaysLocal() {
        val plan = IkarOrchestrator.plan(
            "Mémorise que je préfère le français",
            connectedMode = true,
            shareRelevantMemory = true,
            hasRelevantMemory = false
        )
        assertEquals(IkarOrchestrator.Destination.REMEMBER_LOCALLY, plan.destination)
    }

    @Test
    fun securityGuidanceTakesPriorityOverOnlineModel() {
        val plan = IkarOrchestrator.plan(
            "Comment reconnaître un lien de phishing ?",
            connectedMode = true,
            shareRelevantMemory = false,
            hasRelevantMemory = false
        )
        assertEquals(IkarOrchestrator.Destination.LOCAL_SECURITY_GUIDANCE, plan.destination)
    }

    @Test
    fun explicitDocumentSearchUsesLocalKnowledge() {
        val plan = IkarOrchestrator.plan(
            "Retrouve dans mes documents les informations sur la photosynthèse",
            connectedMode = true,
            shareRelevantMemory = true,
            hasRelevantMemory = false
        )
        assertEquals(IkarOrchestrator.Destination.LOCAL_KNOWLEDGE, plan.destination)
    }

    @Test
    fun matchedLocalKnowledgeIsNotSharedWithoutConsent() {
        val plan = IkarOrchestrator.plan(
            "Explique la photosynthèse",
            connectedMode = true,
            shareRelevantMemory = false,
            hasRelevantMemory = true
        )
        assertEquals(IkarOrchestrator.Destination.LOCAL_KNOWLEDGE, plan.destination)
    }

    @Test
    fun explicitInternetResearchUsesWebResearchProvider() {
        val plan = IkarOrchestrator.plan(
            "Recherche sur Internet : les panneaux solaires",
            connectedMode = false,
            shareRelevantMemory = false,
            hasRelevantMemory = false
        )
        assertEquals(IkarOrchestrator.Destination.WEB_RESEARCH, plan.destination)
    }

    @Test
    fun explicitInternetResearchTakesPriorityOverUnsharedLocalMatches() {
        val plan = IkarOrchestrator.plan(
            "Cherche sur le web les dernières informations sur l'énergie solaire",
            connectedMode = true,
            shareRelevantMemory = false,
            hasRelevantMemory = true
        )
        assertEquals(IkarOrchestrator.Destination.WEB_RESEARCH, plan.destination)
    }

    @Test
    fun connectedGeneralQuestionUsesOnlineModel() {
        val plan = IkarOrchestrator.plan(
            "Explique-moi le fonctionnement des panneaux solaires",
            connectedMode = true,
            shareRelevantMemory = false,
            hasRelevantMemory = false
        )
        assertEquals(IkarOrchestrator.Destination.ONLINE_MODEL, plan.destination)
    }

    @Test
    fun disconnectedGeneralQuestionUsesOfflineAssistant() {
        val plan = IkarOrchestrator.plan(
            "Explique-moi le fonctionnement des panneaux solaires",
            connectedMode = false,
            shareRelevantMemory = false,
            hasRelevantMemory = false
        )
        assertEquals(IkarOrchestrator.Destination.OFFLINE_ASSISTANT, plan.destination)
    }
}
