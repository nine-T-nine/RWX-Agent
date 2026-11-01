package com.corrodinggames.rts.game.ai.custom.micro.case1;

import com.corrodinggames.rts.game.ai.strategy.*;
import com.corrodinggames.rts.game.ai.custom.util.AiMapGraph;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.gameFramework.GameEngine;
import java.io.*;
import java.util.*;

/** CASE1 opponent: opening rally, shared attack-move, and supported low-health retreat. */
public final class Case1EnemyStrategy implements AiStrategy {
    public static final int CODE=14;
    private int nextDecisionTick;
    private double activeAfterFrames;
    private boolean rallyStarted, rallyComplete;
    private float rallyX, rallyY;
    private long sharedTargetId=-1;
    private final Map<Long,Pressure> pressure=new TreeMap<>();
    private static final class Pressure {
        int since=-1,lastHit=-9999;
        boolean retreating;
        float x,y;
    }
    public static AiDefinition definition(){return AiDefinition.custom(CODE,"rwx.micro.case1","CASE1 opponent","CASE1对手",Case1EnemyStrategy::new);}
    @Override public String id(){return "rwx.micro.case1.v1";}
    @Override public void onStart(AiContext context,boolean restored) {
        if(!restored) activeAfterFrames=Double.parseDouble(System.getProperty("rwx.micro.enemyDelaySeconds","0"))*60;
    }
    @Override public void onFrame(AiContext context,float delta) {
        if(activeAfterFrames>0){activeAfterFrames-=delta;return;}
        if(context.tick()<nextDecisionTick)return;
        nextDecisionTick=context.tick()+12;
        List<OrderableUnit> units=context.ownUnits().stream().filter(u->u.r().v().equals("c_mammothTank")).sorted(Comparator.comparingLong(u->u.objectId)).toList();
        if(units.isEmpty())return;
        pressure.keySet().removeIf(id->units.stream().noneMatch(u->u.objectId==id));
        if(!rallyStarted) {
            OrderableUnit anchor=units.get(new Random(units.get(0).objectId^context.tick()).nextInt(units.size()));
            rallyX=anchor.posX;rallyY=anchor.posY;rallyStarted=true;
            context.orders().move(rallyX,rallyY,units.toArray(new OrderableUnit[0]));
            return;
        }
        boolean launching=!rallyComplete;
        if(!rallyComplete) {
            double radius=40+units.stream().mapToDouble(u->u.radius).max().orElse(0)*Math.sqrt(units.size());
            if(units.stream().anyMatch(u->Math.hypot(u.posX-rallyX,u.posY-rallyY)>radius))return;
            rallyComplete=true;
        }
        List<BaseUnit> enemies=context.visibleEnemies().stream().filter(u->u.team.teamId==Case1Duel.SELF_TEAM).sorted(Comparator.comparingLong(u->u.objectId)).toList();
        BaseUnit sharedTarget=enemies.stream().filter(u->u.objectId==sharedTargetId).findFirst().orElse(null);
        if(sharedTarget==null) {
            double centerX=units.stream().mapToDouble(u->u.posX).average().orElse(rallyX);
            double centerY=units.stream().mapToDouble(u->u.posY).average().orElse(rallyY);
            sharedTarget=enemies.stream().min(Comparator.comparingDouble(u->Math.hypot(u.posX-centerX,u.posY-centerY))).orElse(null);
            sharedTargetId=sharedTarget==null?-1:sharedTarget.objectId;
        }
        for(OrderableUnit unit:units) {
            if(pressureRetreat(context,unit,enemies))continue;
            boolean lowHealth=unit.currentHealth<=unit.maxHealth*0.2f;
            BaseUnit target=unit.attackTarget;
            if(lowHealth||target==null||target.isDead) {
                target=enemies.stream().min(Comparator.comparingDouble(u->Math.hypot(u.posX-unit.posX,u.posY-unit.posY))).orElse(null);
            }
            if(target!=null&&!target.isDead&&target.team!=null&&target.team.teamId==Case1Duel.SELF_TEAM&&target.r().v().equals("mechMinigun")) {
                double distance=Math.hypot(unit.posX-target.posX,unit.posY-target.posY);
                float range=unit.m();
                BaseUnit threat=target;
                // A shorter-ranged mammoth cannot kite safely by distance alone.
                // Yield extra ground only while a healthier ally can cover the retreat.
                boolean covered=lowHealth&&units.stream().anyMatch(ally->ally!=unit
                        &&ally.currentHealth/ally.maxHealth>unit.currentHealth/unit.maxHealth
                        &&Math.hypot(ally.posX-unit.posX,ally.posY-unit.posY)<260
                        &&Math.hypot(ally.posX-threat.posX,ally.posY-threat.posY)<=ally.m());
                float retreatRange=covered?Math.max(range,((OrderableUnit)target).m()+35):range;
                if(distance<retreatRange-2) {
                    float step=lowHealth?90:60;
                    float destinationRange=(float)Math.min(retreatRange,distance+step);
                    float[] point=retreatPoint(unit.posX,unit.posY,target.posX,target.posY,destinationRange,unit.objectId);
                    AiMapGraph graph=AiMapGraph.of(unit.getMovementType());
                    boolean retreating=false;
                    // Stay on the requested radial segment: never teleport or route through water.
                    for(double scale=1;scale>=0.125;scale-=0.125) {
                        float x=unit.posX+(point[0]-unit.posX)*(float)scale;
                        float y=unit.posY+(point[1]-unit.posY)*(float)scale;
                        if(graph!=null&&graph.passable(AiMapGraph.worldToTileX(x),AiMapGraph.worldToTileY(y))&&context.canReach(unit,x,y)) {
                            UnitCommand old=unit.getCurrentWaypoint();
                            if(!same(old,UnitCommandType.move,x,y,8))context.orders().move(x,y,unit);
                            retreating=true;
                            break;
                        }
                    }
                    // If terrain blocks every retreat point, let native attack-move fight.
                    if(retreating)continue;
                }
            }
            UnitCommand current=unit.getCurrentWaypoint();
            if(!launching&&current!=null&&current.getCommandType()==UnitCommandType.move&&target!=null&&!target.isDead
                    &&Math.hypot(unit.posX-current.getTargetX(),unit.posY-current.getTargetY())>8)continue;
            if(sharedTarget!=null&&!same(current,UnitCommandType.attackMove,sharedTarget.posX,sharedTarget.posY,20))context.orders().attackMove(sharedTarget.posX,sharedTarget.posY,unit);
        }
    }
    private boolean pressureRetreat(AiContext context,OrderableUnit unit,List<BaseUnit> enemies) {
        Pressure state=pressure.computeIfAbsent(unit.objectId,id->new Pressure());
        int now=GameEngine.getInstance().gameTimeMillis;
        if(state.retreating) {
            if(unit.getCurrentWaypoint()!=null&&Math.hypot(unit.posX-state.x,unit.posY-state.y)>8)return true;
            state.retreating=false;state.since=-1;state.lastHit=unit.bs;
        }
        boolean charging=false;
        for(int i=0;i<unit.getTechLevel();i++) {
            UnitMovementData weapon=unit.movementLevels[i];
            if(unit.r(i)&&weapon.isActive&&weapon.rotation<=0&&weapon.speed>0)charging=true;
        }
        if(charging||now-unit.bs>1000)state.since=-1;
        else if(unit.bs!=state.lastHit&&(state.since<0||unit.bs-state.lastHit>1000))state.since=unit.bs;
        state.lastHit=unit.bs;
        if(state.since<0||now-state.since<3000)return false;
        BaseUnit threat=unit.unitTarget1;
        if(threat==null||threat.isDead||threat.team.teamId!=Case1Duel.SELF_TEAM)
            threat=enemies.stream().min(Comparator.comparingDouble(u->Math.hypot(u.posX-unit.posX,u.posY-unit.posY))).orElse(null);
        if(threat==null)return false;
        float range=(float)Math.hypot(unit.posX-threat.posX,unit.posY-threat.posY)+60;
        float[] point=retreatPoint(unit.posX,unit.posY,threat.posX,threat.posY,range,unit.objectId);
        AiMapGraph graph=AiMapGraph.of(unit.getMovementType());
        if(graph==null||!graph.passable(AiMapGraph.worldToTileX(point[0]),AiMapGraph.worldToTileY(point[1]))
                ||!context.canReach(unit,point[0],point[1]))return false;
        state.x=point[0];state.y=point[1];state.retreating=true;state.since=-1;
        context.orders().move(state.x,state.y,unit);
        return true;
    }
    private static boolean same(UnitCommand current,UnitCommandType type,float x,float y,float tolerance) {
        return current!=null&&current.getCommandType()==type&&Math.hypot(current.getTargetX()-x,current.getTargetY()-y)<=tolerance;
    }
    /** Exact range projection before terrain checks; a stable axis resolves coincident centers. */
    public static float[] retreatPoint(float x,float y,float tx,float ty,float range,long id) {
        double dx=x-tx,dy=y-ty,length=Math.hypot(dx,dy);
        if(length<0.0001){dx=(id&1)==0?1:-1;dy=0;length=1;}
        return new float[]{(float)(tx+dx/length*range),(float)(ty+dy/length*range)};
    }
    @Override public void writeState(DataOutputStream out)throws IOException{
        out.writeInt(nextDecisionTick);out.writeDouble(activeAfterFrames);out.writeBoolean(rallyStarted);out.writeBoolean(rallyComplete);out.writeFloat(rallyX);out.writeFloat(rallyY);out.writeLong(sharedTargetId);
        out.writeInt(pressure.size());
        for(var entry:pressure.entrySet()) {
            Pressure state=entry.getValue();out.writeLong(entry.getKey());out.writeInt(state.since);out.writeInt(state.lastHit);out.writeBoolean(state.retreating);out.writeFloat(state.x);out.writeFloat(state.y);
        }
    }
    @Override public void readState(DataInputStream in)throws IOException{
        nextDecisionTick=in.readInt();activeAfterFrames=in.readDouble();rallyStarted=in.readBoolean();rallyComplete=in.readBoolean();rallyX=in.readFloat();rallyY=in.readFloat();sharedTargetId=in.readLong();
        pressure.clear();int count=in.readInt();
        for(int i=0;i<count;i++) {
            long id=in.readLong();Pressure state=new Pressure();state.since=in.readInt();state.lastHit=in.readInt();state.retreating=in.readBoolean();state.x=in.readFloat();state.y=in.readFloat();pressure.put(id,state);
        }
    }
}
