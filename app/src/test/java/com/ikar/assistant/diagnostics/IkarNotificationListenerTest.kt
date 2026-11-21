package com.ikar.assistant.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class IkarNotificationListenerTest {
    @Test fun verificationCodeIsClassifiedAsSensitive() {
        assertEquals("Sensible — contenu non conservé", IkarNotificationListener.classifyNotification("Code de vérification", "Votre code est 123456"))
    }

    @Test fun financialAlertIsClassifiedWithoutKeepingContent() {
        assertEquals("Finances — contenu non conservé", IkarNotificationListener.classifyNotification("Banque", "Une transaction a été effectuée"))
    }

    @Test fun ordinaryNotificationIsNotMarkedAsSensitive() {
        assertEquals("Notification ordinaire — contenu non conservé", IkarNotificationListener.classifyNotification("Météo", "Il fera beau demain"))
    }
}
