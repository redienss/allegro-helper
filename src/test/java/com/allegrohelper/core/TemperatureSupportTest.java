package com.allegrohelper.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which models the describe step sends a {@code temperature} to. Getting this
 * wrong is not fatal — the API rejection is caught and retried — but a wrong
 * <em>true</em> costs a wasted round trip per run, and a wrong <em>false</em>
 * silently drops the dial that keeps descriptions from drifting.
 */
class TemperatureSupportTest {

    @Test
    void reasoningModelsDoNotTakeATemperature() {
        assertFalse(GenerateDescription.supportsTemperature("gpt-5"));
        assertFalse(GenerateDescription.supportsTemperature("gpt-5-mini"));
        assertFalse(GenerateDescription.supportsTemperature("gpt-5-nano"));
        assertFalse(GenerateDescription.supportsTemperature("o1"));
        assertFalse(GenerateDescription.supportsTemperature("o1-mini"));
        assertFalse(GenerateDescription.supportsTemperature("o3-mini"));
        assertFalse(GenerateDescription.supportsTemperature("o4-mini"));
    }

    @Test
    void chatModelsTakeATemperature() {
        assertTrue(GenerateDescription.supportsTemperature("gpt-4o-mini")); // the built-in default
        assertTrue(GenerateDescription.supportsTemperature("gpt-4o"));
        assertTrue(GenerateDescription.supportsTemperature("gpt-4.1"));
    }

    @Test
    void theChatVariantOfGpt5IsNotAReasoningModel() {
        assertTrue(GenerateDescription.supportsTemperature("gpt-5-chat"));
        assertTrue(GenerateDescription.supportsTemperature("gpt-5-chat-latest"));
    }

    @Test
    void caseAndSurroundingSpaceDoNotMatter() {
        // The model name comes from .env or the settings dialog, both hand-typed.
        assertFalse(GenerateDescription.supportsTemperature("  GPT-5  "));
        assertFalse(GenerateDescription.supportsTemperature("O3-Mini"));
        assertTrue(GenerateDescription.supportsTemperature(" GPT-4o-Mini "));
    }

    @Test
    void anUnknownModelIsAssumedToSupportIt() {
        // The retry covers being wrong here; assuming the opposite would quietly
        // drop the temperature for every model this list has not been taught.
        assertTrue(GenerateDescription.supportsTemperature("llama-3.1-70b"));
        assertTrue(GenerateDescription.supportsTemperature("mistral-large"));
        assertTrue(GenerateDescription.supportsTemperature("openai/gpt-4o"));
        assertTrue(GenerateDescription.supportsTemperature(""));
        assertTrue(GenerateDescription.supportsTemperature(null));
    }
}
