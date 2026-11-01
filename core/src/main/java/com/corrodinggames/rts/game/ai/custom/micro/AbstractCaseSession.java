package com.corrodinggames.rts.game.ai.custom.micro;

import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxCliCommands.Command;
import com.corrodinggames.rts.game.units.OrderableUnit;
import com.corrodinggames.rts.gameFramework.GameEngine;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/**
 * Shared session scaffolding: the violation sink, tick stamping and the native-command authority
 * template. A case only says which native command shape is legal for each instruction it created
 * ({@link #matches}) and where its own evidence rows go ({@link #nativeLog}).
 */
public abstract class AbstractCaseSession implements MicroSession {
    /** One accepted request and the native commands it is allowed to produce. */
    public static final class Instruction {
        public long request,actor,target;
        public List<Long> units;
        public String kind,unitType;
        public OrderableUnit unit;
        public boolean terminal,applied;
    }
    protected final MicroRuntime run;
    protected final GameEngine engine=GameEngine.getInstance();
    protected final Map<String,Object> meta;
    /** Native commands this session created; anything else is a bypass. */
    protected final Map<com.corrodinggames.rts.gameFramework.Command,Instruction> permitted=new IdentityHashMap<>();
    protected final BufferedWriter violations;
    private long violationSeq,nativeIndex;

    protected AbstractCaseSession(MicroRuntime run,Map<String,Object> meta)throws IOException {
        this.run=run;this.meta=meta;
        Files.createDirectories(run.writer.directory.resolve("engine"));
        violations=Files.newBufferedWriter(run.writer.directory.resolve("violations.jsonl"));
    }
    /** Registers a fresh instruction for the native commands the caller is about to create. */
    protected Instruction instruction(String kind,OrderableUnit actor,List<Long> units) {
        Instruction i=new Instruction();MicroRuntime.Request q=MicroRuntime.request();
        i.request=q==null?0:q.seq;i.kind=kind;i.actor=actor.objectId;i.units=units;i.unit=actor;
        return i;
    }
    protected Map<String,Object> stamp(){return map("tick",engine.currentTick,"gameMs",engine.gameTimeMillis,"wallMs",run.wall());}
    protected void emit(BufferedWriter writer,Map<String,Object> row){row.putAll(stamp());MicroRunWriter.line(writer,row);}
    public String reject(String raw,List<Long> actors,Object target,String type,String reason) {
        MicroRuntime.Request q=MicroRuntime.request();if(q!=null)q.violation=true;
        Map<String,Object> row=map("violationId",++violationSeq,"requestId",q==null?null:q.seq,"command",raw,"actor",actors,
                "target",target,"violationType",type,"reason",reason,"rejected",true);
        row.putAll(stamp());MicroRunWriter.line(violations,row);return MicroRuntime.error("violation: "+reason);
    }
    /** Guards a command that needs the match to be running. */
    protected void running() {
        if(!run.phase.equals("running"))throw new IllegalArgumentException("invalid_request: not_running");
    }
    /** Case-specific shape check for a native command this session created. */
    protected abstract boolean matches(com.corrodinggames.rts.gameFramework.Command command,Instruction instruction);
    /** Writer that receives this case's native-command rows. */
    protected abstract BufferedWriter nativeLog();
    /** Row shape of a native-command record; cases may add their own fields. */
    protected Map<String,Object> nativeRow(Instruction i,int index) {
        return map("kind","native","requestId",i.request,"nativeIndex",index,"actor",i.actor,"operation",i.kind);
    }
    /** Closes the case's own evidence writers; the violations writer is closed by {@link #close()}. */
    protected abstract void closeLogs() throws IOException;

    /** Only the exact commands this session created may reach the engine or the replay. */
    public boolean allow(com.corrodinggames.rts.gameFramework.Command command) {
        Instruction i=permitted.get(command);
        boolean valid=i!=null&&!i.terminal&&run.phase.equals("running")&&i.units!=null
                &&new HashSet<>(command.getUnitIds()).equals(new HashSet<>(i.units))
                &&!command.isSystemAction&&!command.stopCurrentAction&&!command.isInstantCommand
                &&!command.isHighPriority&&!command.isQueued
                &&command.attackMode==null&&command.targetPoint==null&&!command.hasRallyPoint();
        if(valid)valid=matches(command,i);
        if(!valid)reject("native",command.getUnitIds(),command.actionId==null?null:command.actionId.getId(),
                "native_bypass","unauthorized_native_command");
        return valid;
    }
    public void recorded(com.corrodinggames.rts.gameFramework.Command command) {
        Instruction i=permitted.get(command);if(i==null)return;
        emit(nativeLog(),nativeRow(i,(int)nativeIndex++));
    }
    public void applied(com.corrodinggames.rts.gameFramework.Command command) {
        Instruction i=permitted.get(command);if(i==null)return;i.applied=true;
    }
    @Override public void close()throws IOException{closeLogs();violations.close();}
}
