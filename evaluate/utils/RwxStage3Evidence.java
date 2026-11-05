package evaluate.utils;

import com.corrodinggames.rts.game.PlayerTeam;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.GameObject;
import java.io.IOException;
import java.util.*;

/** Raw, seek-safe evidence for the three Stage-3 judgements. */
final class RwxStage3Evidence {
    interface Writer { void write(String kind, Map<String,Object> fields) throws IOException; }

    private static final Set<String> ARTILLERY=Set.of("artillery","c_artillery","heavyArtillery","mechArtillery");
    private static final Set<String> BUILDERS=Set.of("builder","builderShip","combatEngineer","mechEngineer");
    private static final Set<String> INTERCEPTORS=Set.of("airShip","interceptor","c_interceptor","heavyInterceptor");
    private static final Set<String> TRANSPORTS=Set.of("dropship","hovercraft","experimentalDropship");
    /** Units whose production menu belongs to a land, mech or experimental-land factory. */
    private static final Set<String> FACTORY_COMBAT=Set.of(
            "artillery","c_artillery","laserTank","c_laserTank","mammothTank","c_mammothTank",
            "heavyArtillery","heavyHoverTank","heavyTank","missileTank","plasmaTank","scout",
            "experimentalTank","c_experimentalTank","combatEngineer","experimentalDropship",
            "experimentalHoverTank","experimentalSpider","fireBee","nautilusLand","nautilusSubmarineLand",
            "mechArtillery","mechBunker","mechEngineer","mechFlame","mechHeavyMissile","mechLaser",
            "mechLightning","mechMinigun");

    private final GameEngine engine;
    private final PlayerTeam team;
    private final Writer writer;
    private final double radius, buildingMultiplier, deadExtractorMultiplier, createRatio, keepRatio;
    private final double enemyMinimum;
    private final long artilleryToleranceMs, zoneCheckMs, recentDeathMs, graceMs;
    private final int clearChecks;
    private final Map<Long,Ambush> ambushes=new LinkedHashMap<>();
    private final Map<Long,Barrage> barrages=new LinkedHashMap<>();
    private final List<Death> pendingDeaths=new ArrayList<>();
    private final ArrayDeque<Death> recentDeaths=new ArrayDeque<>();
    private final List<Zone> zones=new ArrayList<>();
    private long eventNumber,zoneNumber,nextZoneCheck;

    private static final class Ambush {
        final String id; final long unitId,fromMs; final String type; final double maxHp,maxShield,cost;
        double hpLoss,shieldLoss; int hits;
        Ambush(String id,BaseUnit u,long time){this.id=id;unitId=u.objectId;fromMs=time;type=type(u);
            maxHp=Math.max(0,u.maxHealth);maxShield=Math.max(0,u.unitEnergyMax);cost=cost(u);}
    }
    private static final class Barrage {
        final String id; final long unitId,fromMs; final String type; final double maxHp,maxShield,cost;
        double hpLoss,shieldLoss; int hits; long secondMs=-1,lastMs;
        Barrage(String id,BaseUnit u,long time){this.id=id;unitId=u.objectId;fromMs=lastMs=time;type=type(u);
            maxHp=Math.max(0,u.maxHealth);maxShield=Math.max(0,u.unitEnergyMax);cost=cost(u);}
    }
    private static final class Death {
        final long id,time; final String type; final float x,y; final double cost; final boolean building,extractor;
        final boolean damageKilled,transported,builder;
        Death(BaseUnit u,BaseUnit killer,long time){id=u.objectId;this.time=time;type=type(u);x=u.posX;y=u.posY;
            cost=cost(u);building=u.bI();extractor="extractor".equals(type);damageKilled=EngineEvidence.eligible(killer)&&!reclaimed(u);
            transported=u.transportContainer!=null;builder=BUILDERS.contains(type);}
    }
    private static final class Zone {
        final long id,birth; final float x,y; int fail; boolean active=true;
        Zone(long id,long birth,float x,float y){this.id=id;this.birth=birth;this.x=x;this.y=y;}
    }
    private record Ratio(double enemy,double friendly,double value) { }

    RwxStage3Evidence(GameEngine engine,PlayerTeam team,double radius,Writer writer) throws IOException {
        this.engine=engine;this.team=team;this.radius=radius;this.writer=writer;
        buildingMultiplier=property("rwx.evaluate.stage3.R",2);
        deadExtractorMultiplier=property("rwx.evaluate.stage3.R2",.5);
        createRatio=property("rwx.evaluate.stage3.UCreate",.7);
        keepRatio=property("rwx.evaluate.stage3.UKeep",.4);
        enemyMinimum=property("rwx.evaluate.stage3.enemyMinimum",1500);
        zoneCheckMs=longProperty("rwx.evaluate.stage3.zoneCheckMs",4000);
        recentDeathMs=longProperty("rwx.evaluate.stage3.recentDeathMs",5000);
        graceMs=longProperty("rwx.evaluate.stage3.graceMs",5000);
        clearChecks=(int)longProperty("rwx.evaluate.stage3.clearChecks",2);
        long measured=artilleryIntervalMs();
        artilleryToleranceMs=longProperty("rwx.evaluate.stage3.artilleryToleranceMs",Math.round(measured*1.3));
        if(zoneCheckMs<=0||recentDeathMs<0||graceMs<0||artilleryToleranceMs<=0||clearChecks<=0
                ||buildingMultiplier<0||deadExtractorMultiplier<0||enemyMinimum<0
                ||createRatio<0||createRatio>1||keepRatio<0||keepRatio>1)
            throw new IllegalArgumentException("invalid rwx.evaluate.stage3 configuration");
        nextZoneCheck=((long)engine.gameTimeMillis/zoneCheckMs+1)*zoneCheckMs;
        writer.write("stage3_meta",EngineEvidence.data("schema",1,"X",radius,"artilleryKMs",measured,
                "artilleryToleranceMs",artilleryToleranceMs,"zoneCheckMs",zoneCheckMs,"R",buildingMultiplier,
                "R2",deadExtractorMultiplier,"recentDeathMs",recentDeathMs,"UCreate",createRatio,
                "UKeep",keepRatio,"enemyMinimum",enemyMinimum,"clearChecks",clearChecks,"graceMs",graceMs));
    }

    private static double property(String name,double fallback){return Double.parseDouble(System.getProperty(name,Double.toString(fallback)));}
    private static long longProperty(String name,long fallback){return Long.getLong(name,fallback);}
    private static String type(BaseUnit unit){return unit.r().v();}
    private static double cost(BaseUnit unit){return Math.max(0,unit.cL());}
    private String side(BaseUnit unit){return unit.team==team?"self":team.c(unit.team)?"enemy":"ally";}
    private static boolean builder(BaseUnit unit){return BUILDERS.contains(type(unit))||unit.r().l();}

    private long artilleryIntervalMs(){
        double ticks=0;
        List<UnitType> types=new ArrayList<>();types.add(UnitTypeEnum.artillery);types.addAll(UnitTypeEnum.ae);
        for(UnitType candidate:types)if(candidate!=null&&ARTILLERY.contains(candidate.v()))try{
            BaseUnit prototype=BaseUnit.findTurretPosition(candidate);
            if(prototype instanceof OrderableUnit orderable)ticks=Math.max(ticks,orderable.b(0));
        }catch(RuntimeException ignored){ }
        // The stock artillery is 240 simulation ticks (4 seconds). Keep a deterministic fallback
        // for early test fixtures where custom unit definitions have not been loaded yet.
        return Math.round((ticks>0?ticks:240)/60d*1000d);
    }

    private boolean idleForAmbush(BaseUnit unit){
        if(unit.bI()||unit.transportContainer!=null||unit.buildProgress<1||!(unit instanceof OrderableUnit orderable))return false;
        UnitCommand waypoint=orderable.getCurrentWaypoint();
        if(builder(unit)&&waypoint!=null&&(waypoint.getCommandType()==UnitCommandType.build
                ||waypoint.getCommandType()==UnitCommandType.repair))return true;
        if(waypoint!=null||orderable.attackTarget!=null||unit.attackTargetUnit!=null)return false;
        return orderable.lastWeaponFireMillis<0||engine.gameTimeMillis-orderable.lastWeaponFireMillis>1000;
    }

    void damage(BaseUnit victim,BaseUnit attacker,float healthBefore,float shieldBefore) throws IOException {
        if(!EngineEvidence.eligible(victim)||!EngineEvidence.eligible(attacker)||victim.team!=team||!"enemy".equals(side(attacker)))return;
        double hp=Math.max(0,Math.max(0,healthBefore)-Math.max(0,victim.currentHealth));
        double shield=Math.max(0,Math.max(0,shieldBefore)-Math.max(0,victim.shield));
        long now=engine.gameTimeMillis;
        if(!victim.bI()&&idleForAmbush(victim)){
            Ambush open=ambushes.computeIfAbsent(victim.objectId,id->new Ambush("A-"+(++eventNumber),victim,now));
            open.hpLoss+=hp;open.shieldLoss+=shield;open.hits++;
            writer.write("stage3_ambush",EngineEvidence.data("phase","hit","eventId",open.id,"unitId",open.unitId,
                    "type",open.type,"fromMs",open.fromMs,"DHp",open.hpLoss,"DShield",open.shieldLoss,"N",open.hits,
                    "deltaHp",hp,"deltaShield",shield,"maxHp",open.maxHp,"maxShield",open.maxShield,"cost",open.cost));
        }
        if(victim.bI()&&ARTILLERY.contains(type(attacker))){
            Barrage open=barrages.computeIfAbsent(victim.objectId,id->new Barrage("H-"+(++eventNumber),victim,now));
            open.hpLoss+=hp;open.shieldLoss+=shield;open.hits++;open.lastMs=now;if(open.hits==2)open.secondMs=now;
            writer.write("stage3_barrage",EngineEvidence.data("phase","hit","eventId",open.id,"unitId",open.unitId,
                    "type",open.type,"fromMs",open.fromMs,"secondMs",open.secondMs<0?null:open.secondMs,"lastMs",open.lastMs,
                    "DHp",open.hpLoss,"DShield",open.shieldLoss,"N",open.hits,"deltaHp",hp,"deltaShield",shield,
                    "maxHp",open.maxHp,"maxShield",open.maxShield,"cost",open.cost,"attackerType",type(attacker)));
        }
    }

    void removed(BaseUnit unit,BaseUnit killer) throws IOException {
        if(!EngineEvidence.eligible(unit))return;
        boolean killed=EngineEvidence.eligible(killer)&&!reclaimed(unit);
        closeAmbush(unit.objectId,killed?"DEATH":"REMOVED",killed);
        closeBarrage(unit.objectId,killed?"DESTROYED":"REMOVED",killed);
        if(unit.team==team)pendingDeaths.add(new Death(unit,killer,engine.gameTimeMillis));
    }

    void frame() throws IOException {
        long now=engine.gameTimeMillis;
        for(Iterator<Map.Entry<Long,Ambush>> it=ambushes.entrySet().iterator();it.hasNext();){
            Map.Entry<Long,Ambush> entry=it.next();BaseUnit unit=GameObject.a(entry.getKey(),true);
            if(unit==null||unit.isDead||unit.isDestroyed){writeAmbush(entry.getValue(),"DEATH",true);it.remove();}
            else if(!idleForAmbush(unit)){writeAmbush(entry.getValue(),"ACTION",false);it.remove();}
        }
        for(Iterator<Map.Entry<Long,Barrage>> it=barrages.entrySet().iterator();it.hasNext();){
            Map.Entry<Long,Barrage> entry=it.next();
            if(now-entry.getValue().lastMs>artilleryToleranceMs){writeBarrage(entry.getValue(),"EXPIRED",false);it.remove();}
        }
        processDeaths(now);
    }

    void close(String reason) throws IOException {
        boolean complete=Set.of("match_result","replay_end","match_left").contains(reason);
        for(Ambush open:ambushes.values())writeAmbush(open,"EVIDENCE_END",false,complete);
        ambushes.clear();
        for(Barrage open:barrages.values())writeBarrage(open,"EVIDENCE_END",false,complete);
        barrages.clear();
        processDeaths(engine.gameTimeMillis);
    }

    private void closeAmbush(long id,String reason,boolean died) throws IOException {Ambush open=ambushes.remove(id);if(open!=null)writeAmbush(open,reason,died);}
    private void writeAmbush(Ambush open,String reason,boolean died) throws IOException {writeAmbush(open,reason,died,true);}
    private void writeAmbush(Ambush open,String reason,boolean died,boolean complete) throws IOException {
        writer.write("stage3_ambush",EngineEvidence.data("phase","close","eventId",open.id,"unitId",open.unitId,
                "type",open.type,"fromMs",open.fromMs,"toMs",(long)engine.gameTimeMillis,
                "durationMs",Math.max(0,engine.gameTimeMillis-open.fromMs),"closeReason",reason,"died",died,"complete",complete,
                "DHp",open.hpLoss,"DShield",open.shieldLoss,"N",open.hits,"maxHp",open.maxHp,
                "maxShield",open.maxShield,"cost",open.cost));
    }
    private void closeBarrage(long id,String reason,boolean destroyed) throws IOException {Barrage open=barrages.remove(id);if(open!=null)writeBarrage(open,reason,destroyed);}
    private void writeBarrage(Barrage open,String reason,boolean destroyed) throws IOException {writeBarrage(open,reason,destroyed,true);}
    private void writeBarrage(Barrage open,String reason,boolean destroyed,boolean complete) throws IOException {
        writer.write("stage3_barrage",EngineEvidence.data("phase","close","eventId",open.id,"unitId",open.unitId,
                "type",open.type,"fromMs",open.fromMs,"secondMs",open.secondMs<0?null:open.secondMs,"toMs",(long)engine.gameTimeMillis,
                "closeReason",reason,"destroyed",destroyed,"complete",complete,"DHp",open.hpLoss,
                "DShield",open.shieldLoss,"N",open.hits,"maxHp",open.maxHp,"maxShield",open.maxShield,"cost",open.cost));
    }

    private void processDeaths(long now) throws IOException {
        pendingDeaths.sort(Comparator.comparingLong(d->d.id));
        recentDeaths.addAll(pendingDeaths);
        while(!recentDeaths.isEmpty()&&now-recentDeaths.peekFirst().time>recentDeathMs)recentDeaths.removeFirst();
        List<Death> triggers=new ArrayList<>();
        for(Death death:pendingDeaths)if(death.damageKilled&&(death.building
                ||FACTORY_COMBAT.contains(death.type)&&death.cost>=700))triggers.add(death);

        List<Death> remaining=new ArrayList<>();
        for(Death trigger:triggers){
            List<Zone> covered=covering(trigger.x,trigger.y);
            if(covered.isEmpty())remaining.add(trigger);else for(Zone zone:covered){zone.fail=0;
                writer.write("stage3_zone",EngineEvidence.data("action","refresh","zoneId",zone.id,"triggerId",trigger.id));}
        }
        while(nextZoneCheck<=now){checkZones();nextZoneCheck+=zoneCheckMs;}
        while(!remaining.isEmpty()){
            Death anchor=remaining.remove(0);List<Long> swallowed=new ArrayList<>();swallowed.add(anchor.id);
            remaining.removeIf(other->{if(distance(anchor.x,anchor.y,other.x,other.y)<=radius){swallowed.add(other.id);return true;}return false;});
            Ratio ratio=ratio(anchor.x,anchor.y,now);
            if(ratio.value>=createRatio&&ratio.enemy>=enemyMinimum){Zone zone=new Zone(++zoneNumber,now,anchor.x,anchor.y);zones.add(zone);
                writer.write("stage3_zone",EngineEvidence.data("action","create","zoneId",zone.id,"cx",zone.x,"cy",zone.y,
                        "radius",radius,"birthMs",zone.birth,"triggerIds",swallowed,"E",ratio.enemy,"F",ratio.friendly,"ratio",ratio.value));}
            else writer.write("stage3_zone",EngineEvidence.data("action","drop","cx",anchor.x,"cy",anchor.y,"triggerIds",swallowed,
                    "E",ratio.enemy,"F",ratio.friendly,"ratio",ratio.value,
                    "reason",ratio.enemy<enemyMinimum?"ENEMY_MINIMUM":"RATIO"));
        }
        for(Death death:pendingDeaths)if(death.builder)writeBuilderDeath(death);
        pendingDeaths.clear();
    }

    private void checkZones() throws IOException {
        long now=engine.gameTimeMillis;
        for(Zone zone:zones)if(zone.active){Ratio ratio=ratio(zone.x,zone.y,now);
            boolean keep=ratio.value>=keepRatio&&ratio.enemy>=.5*enemyMinimum;zone.fail=keep?0:zone.fail+1;
            writer.write("stage3_zone",EngineEvidence.data("action","check","zoneId",zone.id,"E",ratio.enemy,"F",ratio.friendly,
                    "ratio",ratio.value,"qualified",keep,"fail",zone.fail));
            if(zone.fail>=clearChecks){zone.active=false;writer.write("stage3_zone",EngineEvidence.data("action","clear","zoneId",zone.id,
                    "clearMs",now,"E",ratio.enemy,"F",ratio.friendly,"ratio",ratio.value));}
        }
    }

    private void writeBuilderDeath(Death death) throws IOException {
        Zone selected=null;
        for(Zone zone:covering(death.x,death.y))if(selected==null||zone.birth<selected.birth)selected=zone;
        long age=selected==null?Long.MIN_VALUE:death.time-selected.birth-graceMs;
        writer.write("stage3_builder_death",EngineEvidence.data("unitId",death.id,"type",death.type,"deathMs",death.time,
                "x",death.x,"y",death.y,"weight",Set.of("combatEngineer","mechEngineer").contains(death.type)?10:1,
                "damageKilled",death.damageKilled,"transported",death.transported,"zoneId",selected==null?null:selected.id,
                "zoneBirthMs",selected==null?null:selected.birth,"ageMs",selected==null?null:age,
                "scoreable",death.damageKilled&&!death.transported&&selected!=null&&age>0));
    }

    private List<Zone> covering(float x,float y){List<Zone> result=new ArrayList<>();for(Zone zone:zones)
        if(zone.active&&distance(x,y,zone.x,zone.y)<=radius)result.add(zone);return result;}
    private static double distance(float x1,float y1,float x2,float y2){return Math.hypot(x1-x2,y1-y2);}

    private Ratio ratio(float x,float y,long now){
        double enemy=0,friendly=0;
        Iterable<BaseUnit> candidates=engine.unitSpatialIndex.e==null?BaseUnit.getGlobalUnitList()
                :engine.unitSpatialIndex.a(x,y,(float)radius);
        for(BaseUnit unit:candidates){
            if(!EngineEvidence.eligible(unit)||unit.isDead||unit.isDestroyed
                    ||distance(x,y,unit.posX,unit.posY)>radius)continue;
            if(unit.team==team){double value;
                if("commandCenter".equals(type(unit)))value=99999;
                else if("extractor".equals(type(unit)))value=0;
                else value=cost(unit);
                friendly+=unit.bI()?value*buildingMultiplier:value;
            }else if(team.c(unit.team)&&enemyThreat(unit))enemy+=cost(unit);
        }
        for(Death death:recentDeaths)if(now-death.time<=recentDeathMs&&distance(x,y,death.x,death.y)<=radius){
            if(death.building)friendly+=death.extractor?death.cost*deadExtractorMultiplier:death.cost;
            else friendly+=death.cost;
        }
        return new Ratio(enemy,friendly,enemy+friendly<=0?0:enemy/(enemy+friendly));
    }

    private static boolean enemyThreat(BaseUnit unit){
        if(!(unit instanceof OrderableUnit orderable)||!orderable.canAttack()||!orderable.canAttackSurfaceUnits())return false;
        if(unit.bI())return !"commandCenter".equals(type(unit))&&type(unit).toLowerCase(Locale.ROOT).contains("turret");
        return !builder(unit)&&!INTERCEPTORS.contains(type(unit))&&!TRANSPORTS.contains(type(unit));
    }

    private static boolean reclaimed(BaseUnit victim){
        for(Object raw:BaseUnit.getGlobalUnitList().toArray())if(raw instanceof OrderableUnit unit){
            UnitCommand waypoint=unit.getCurrentWaypoint();
            if(waypoint!=null&&waypoint.getCommandType()==UnitCommandType.reclaim&&waypoint.getTargetUnit()==victim)return true;
        }
        return false;
    }
}
