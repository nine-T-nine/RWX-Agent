package com.corrodinggames.rts.game.ai.strategy;


import com.corrodinggames.rts.gameFramework.network.GameInputStream;
import com.corrodinggames.rts.gameFramework.network.GameOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Per-team scheduler and deferred save restoration. Never runs on clients or in replay playback. */
public final class AiRuntime {
    private String definitionId;
    private AiDefinition definition;
    private AiStrategy strategy;
    private boolean started;
    private String savedId;
    private byte[] savedState;
    private float economyFrames;
    private float tacticsFrames;
    private float strategyFrames;

    private AiRuntime() { }

    public AiRuntime(AiDefinition definition) {
        this.definition = definition;
        this.definitionId = definition.id();
    }

    public boolean matches(AiDefinition selected) { return definitionId.equals(selected.id()); }
    public boolean isStarted() { return started; }

    public boolean overridesUnitProduction() {
        return started && strategy.overridesUnitProduction();
    }

    public boolean overridesBuildingProduction() {
        return started && strategy.overridesBuildingProduction();
    }

    public boolean overridesUpgrades() {
        return started && strategy.overridesUpgrades();
    }

    public boolean overridesTransportWaiting() {
        return started && strategy.overridesTransportWaiting();
    }

    public void update(AiContext context, float deltaFrames, AiDefinition selected) {
        if (!matches(selected)) {
            throw new IllegalStateException("AI save requires definition " + definitionId + "; selected " + selected.id());
        }
        if (definition == null) definition = selected;
        if (!Float.isFinite(deltaFrames) || deltaFrames < 0) {
            throw new IllegalArgumentException("deltaFrames must be finite and non-negative");
        }
        context.setIssuingOrders(true);
        try {
            if (!started) {
                AiStrategy created = definition.create();
                if (savedId != null && !savedId.equals(created.id())) {
                    throw new IllegalStateException("AI save requires strategy " + savedId
                            + "; installed strategy is " + created.id());
                }
                if (savedState != null) {
                    try {
                        created.readState(new DataInputStream(new ByteArrayInputStream(savedState)));
                    } catch (IOException e) {
                        throw new IllegalStateException("Cannot restore AI strategy " + savedId, e);
                    }
                }
                created.onStart(context, savedId != null);
                strategy = created;
                started = true;
                savedId = null;
                savedState = null;
            }
            strategy.onFrame(context, deltaFrames);
            economyFrames += deltaFrames;
            tacticsFrames += deltaFrames;
            strategyFrames += deltaFrames;
            // At most one callback per phase per update. Each receives ALL elapsed frames.
            if (economyFrames >= definition.cadence().economy()) {
                float elapsed = economyFrames;
                economyFrames = 0;
                strategy.onEconomy(context, elapsed);
                strategy.onUnitProduction(context, elapsed);
                strategy.onBuildingProduction(context, elapsed);
                strategy.onUpgrades(context, elapsed);
            }
            if (tacticsFrames >= definition.cadence().tactics()) {
                float elapsed = tacticsFrames;
                tacticsFrames = 0;
                strategy.onTactics(context, elapsed);
            }
            if (strategyFrames >= definition.cadence().strategy()) {
                float elapsed = strategyFrames;
                strategyFrames = 0;
                strategy.onStrategy(context, elapsed);
            }
        } finally {
            context.setIssuingOrders(false);
        }
    }

    public void stop(AiContext context) {
        if (started) {
            strategy.onStop(context);
        }
    }

    public void writeTo(GameOutputStream output) throws IOException {
        output.writeByte(2); // Version 1 was the initial AI-high-only runtime.
        output.writeStringUTF(definitionId);
        output.writeFloat(economyFrames);
        output.writeFloat(tacticsFrames);
        output.writeFloat(strategyFrames);
        if (strategy == null) {
            output.writeStringNullable(savedId);
            output.writeBytesWithLength(savedState == null ? new byte[0] : savedState);
        } else {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream state = new DataOutputStream(buffer);
            strategy.writeState(state);
            state.flush();
            output.writeStringNullable(strategy.id());
            output.writeBytesWithLength(buffer.toByteArray());
        }
    }

    /** No strategy callbacks during deserialization: unit/world loading is still in progress. */
    public static AiRuntime readFrom(GameInputStream input) throws IOException {
        int version = input.readByte();
        if (version != 1 && version != 2) {
            throw new IOException("Unsupported AI runtime state version: " + version);
        }
        AiRuntime runtime = new AiRuntime();
        runtime.definitionId = version == 1 ? "rwx.ai-high" : input.readUTF();
        runtime.economyFrames = input.readFloat();
        runtime.tacticsFrames = input.readFloat();
        runtime.strategyFrames = input.readFloat();
        runtime.savedId = input.readNullableString();
        runtime.savedState = input.readBytesWithLength();
        if (runtime.savedId == null) {
            runtime.savedState = null;
        }
        return runtime;
    }
}
