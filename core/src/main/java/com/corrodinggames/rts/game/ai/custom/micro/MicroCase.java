package com.corrodinggames.rts.game.ai.custom.micro;

import com.corrodinggames.rts.gameFramework.GameEngine;
import java.nio.file.Path;
import java.util.Map;

/**
 * One evaluation case: deterministic layout, its task grammar, its command surface and the session
 * that answers commands. The runtime never branches on a case id; everything case-specific is
 * reached through this interface.
 */
public interface MicroCase {
    /** Values a case derives from its task id; the case validates the grammar itself. */
    record TaskInfo(int mapType,int level,double gameSeconds,double wallSeconds) { }

    String id();
    Path prepareMap(MicroConfig config,Path runDirectory) throws Exception;
    Map<String,Object> layout(MicroConfig config,GameEngine engine);
    void setup(Map<String,Object> meta,GameEngine engine) throws Exception;
    default int controlledTeam() {return 0;}

    /** Validates {@code task} and returns the launch values derived from it. */
    TaskInfo taskInfo(String task);

    /** CLI command name that renders this case's help document, e.g. {@code rwx-micro-3-help}. */
    String helpCommand();

    /** The commands this case accepts; the only place that decides case membership. */
    MicroCommandProfile profile(MicroConfig config);

    /** True when the match runs at 1x immediately and has no begin/pause/step. */
    default boolean startsRunning() {return false;}

    /** True when only {@code mode=agent,track=realtime} is meaningful for this case. */
    default boolean requiresAgentRealtime() {return true;}

    /** True when a scenario failure should still write meta/result before the process exits. */
    default boolean reportsScenarioFailure() {return false;}

    /** Enemy team used when a layout only names one team. */
    default int fallbackEnemyTeam() {return 1;}

    /** Per-run command handler; null only for cases that answer commands without a session. */
    default MicroSession session(MicroRuntime run,Map<String,Object> meta) throws Exception {return null;}
}
