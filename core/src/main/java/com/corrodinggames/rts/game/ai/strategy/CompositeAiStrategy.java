package com.corrodinggames.rts.game.ai.strategy;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compose economy/production/expansion/combat/scouting/transport/micro modules in declared order.
 * Modules are ordinary AiStrategy implementations that override only their relevant callbacks.
 * Each module has an independent, named, versionable save payload.
 */
public final class CompositeAiStrategy implements AiStrategy {
    private final String id;
    private final List<AiStrategy> modules;
    private final Map<String, AiStrategy> byId = new LinkedHashMap<>();

    public CompositeAiStrategy(String id, AiStrategy... modules) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Composite AI requires an id");
        this.id = id;
        this.modules = List.of(modules);
        for (AiStrategy module : this.modules) {
            if (module.id() == null || module.id().isBlank() || byId.putIfAbsent(module.id(), module) != null) {
                throw new IllegalArgumentException("Modules require unique non-blank ids");
            }
        }
    }

    @Override public String id() { return id; }
    @Override public void onStart(AiContext context, boolean restored) {
        for (AiStrategy module : modules) module.onStart(context, restored);
    }
    @Override public void onFrame(AiContext context, float frames) {
        for (AiStrategy module : modules) module.onFrame(context, frames);
    }
    @Override public void onEconomy(AiContext context, float frames) {
        for (AiStrategy module : modules) module.onEconomy(context, frames);
    }
    @Override public boolean overridesUnitProduction() {
        for (AiStrategy module : modules) {
            if (module.overridesUnitProduction()) return true;
        }
        return false;
    }
    @Override public void onUnitProduction(AiContext context, float frames) {
        for (AiStrategy module : modules) module.onUnitProduction(context, frames);
    }
    @Override public boolean overridesBuildingProduction() {
        for (AiStrategy module : modules) {
            if (module.overridesBuildingProduction()) return true;
        }
        return false;
    }
    @Override public void onBuildingProduction(AiContext context, float frames) {
        for (AiStrategy module : modules) module.onBuildingProduction(context, frames);
    }
    @Override public boolean overridesUpgrades() {
        for (AiStrategy module : modules) {
            if (module.overridesUpgrades()) return true;
        }
        return false;
    }
    @Override public void onUpgrades(AiContext context, float frames) {
        for (AiStrategy module : modules) module.onUpgrades(context, frames);
    }
    @Override public void onTactics(AiContext context, float frames) {
        for (AiStrategy module : modules) module.onTactics(context, frames);
    }
    @Override public void onStrategy(AiContext context, float frames) {
        for (AiStrategy module : modules) module.onStrategy(context, frames);
    }
    @Override public void onStop(AiContext context) {
        for (int i = modules.size() - 1; i >= 0; i--) modules.get(i).onStop(context);
    }

    @Override public void writeState(DataOutputStream output) throws IOException {
        output.writeInt(1);
        output.writeInt(modules.size());
        for (AiStrategy module : modules) {
            output.writeUTF(module.id());
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream state = new DataOutputStream(buffer);
            module.writeState(state);
            state.flush();
            byte[] bytes = buffer.toByteArray();
            output.writeInt(bytes.length);
            output.write(bytes);
        }
    }

    @Override public void readState(DataInputStream input) throws IOException {
        if (input.readInt() != 1) throw new IOException("Unsupported composite AI state version");
        if (input.readInt() != modules.size()) throw new IOException("AI module set changed");
        Map<String, AiStrategy> remaining = new LinkedHashMap<>(byId);
        for (int i = 0; i < modules.size(); i++) {
            String moduleId = input.readUTF();
            AiStrategy module = remaining.remove(moduleId);
            if (module == null) throw new IOException("Unknown or duplicate AI module: " + moduleId);
            int length = input.readInt();
            if (length < 0 || length > input.available()) throw new IOException("Invalid AI module payload length");
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            module.readState(new DataInputStream(new ByteArrayInputStream(bytes)));
        }
    }
}
