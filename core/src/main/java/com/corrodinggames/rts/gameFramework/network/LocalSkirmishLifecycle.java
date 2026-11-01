package com.corrodinggames.rts.gameFramework.network;

import com.corrodinggames.rts.game.GameTeam;
import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.ai.AIController;
import com.corrodinggames.rts.gameFramework.GameEngine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Restores room configuration, rather than the state of a finished battle, on a local restart. */
public final class LocalSkirmishLifecycle {
    private LocalSkirmishLifecycle() { }

    public static void prepareRestart(GameEngine engine) {
        NetworkEngine network = engine.networkEngine;
        GameRoomSettings settings = network.roomSettings.clone();
        int slots = PlayerTeam.TEAM_NEUTRAL;
        PlayerTeam local = engine.playerTeam != null ? engine.playerTeam : network.localPlayerTeam;
        int localId = local == null ? -1 : local.teamId;
        List<PlayerTeam> previous = new ArrayList<>();
        for (int id = 0; id < slots; id++) {
            PlayerTeam team = PlayerTeam.k(id);
            if (team != null) previous.add(team);
        }
        PlayerTeam.resetTeamRegistry();
        try {
            PlayerTeam.setMaxTeamId(slots, true);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to restore local team slots", error);
        }
        for (PlayerTeam old : previous) {
            PlayerTeam team = old instanceof AIController
                    ? new AIController(old.teamId) : new GameTeam(old.teamId);
            team.teamName = old.teamName;
            team.teamColorId = old.teamColorId;
            team.teamPingTime = old.teamPingTime;
            team.isTeamSpectator = old.isTeamSpectator;
            team.isTeamObserver = old.isTeamObserver;
            team.isTeamLocked = old.isTeamLocked;
            team.teamAIDifficultyOverride = old.teamAIDifficultyOverride;
            team.teamAILevelOverride = old.teamAILevelOverride;
            team.startingUnitsOverride = old.startingUnitsOverride;
            team.playerColorOverride = old.playerColorOverride;
            team.assignedTeamColorIndex = old.assignedTeamColorIndex;
            team.teamColorIndex = old.teamColorIndex;
            team.teamSortIndex = old.teamSortIndex;
            team.isTeamReady = old.isTeamReady;
        }
        network.roomSettings = settings;
        network.localPlayerTeam = PlayerTeam.k(localId);
        engine.playerTeam = network.localPlayerTeam;
        // The ordinary room-load path applies income, credits, fog and starting units. At the
        // end of GameLogic.loadLevel the local server switches back to non-networked simulation.
        network.networkGameActive = true;
        PlayerTeam.refreshTeamInstances();
    }
}
