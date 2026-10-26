package com.corrodinggames.rts.game.ai.strategy;

/** Simulation frames, at 60 frames per second. Independent of the original AI's timers. */
public record AiCadence(float economy, float tactics, float strategy) {
    public static final AiCadence STANDARD = new AiCadence(25, 80, 250);

    public AiCadence {
        if (!valid(economy) || !valid(tactics) || !valid(strategy)) {
            throw new IllegalArgumentException("AI periods must be finite and positive");
        }
    }

    private static boolean valid(float value) { return Float.isFinite(value) && value > 0; }
}
