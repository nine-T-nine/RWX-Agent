package com.corrodinggames.rts.game.ai.custom.micro;

import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.gameFramework.*;
import java.util.*;
import static com.corrodinggames.rts.game.ai.custom.micro.MicroData.map;

/** Read-only extra fields; ownership/visibility selection stays with the existing CLI service. */
public final class MicroSnapshots {
    private MicroSnapshots(){ }
    public static Map<String,Object> unit(OrderableUnit unit,Map<Long,String> aliases,boolean pending) {
        GameEngine engine=GameEngine.getInstance();UnitCommand order=unit.getCurrentWaypoint();
        List<Map<String,Object>> weapons=new ArrayList<>();
        for(int i=0;i<unit.getTechLevel();i++)if(unit.q(i)>0) {
            UnitMovementData w=unit.movementLevels[i];
            weapons.add(map("index",i,"cooldownFrames",w.rotation,"warmupFrames",w.speed,
                    "target",w.targetUnit==null?null:w.targetUnit.objectId));
        }
        BaseUnit target=unit.attackTarget;
        Map<String,Object> row=map("alias",aliases.get(unit.objectId),"id",unit.objectId,"team",unit.team.teamId,"type",unit.r().v(),
                "x",unit.posX,"y",unit.posY,"hp",Math.max(0,unit.currentHealth),"maxHp",unit.maxHealth,"shield",Math.max(0,unit.shield),
                "maxShield",unit.unitEnergyMax,"alive",!unit.isDead&&!unit.isDestroyed&&unit.currentHealth>0,
                "order",order==null?"idle":order.getCommandType().name(),"orderTarget",order==null||order.getTargetUnit()==null?null:order.getTargetUnit().objectId,
                "target",target==null?null:target.objectId,"orderX",order==null?null:order.getTargetX(),"orderY",order==null?null:order.getTargetY(),
                "weapons",weapons,"lastFireMs",unit.lastWeaponFireMillis,"mode",unit.attackMode==null?null:unit.attackMode.name());
        if(pending) {
            List<Map<String,Object>> queued=new ArrayList<>();
            for(Command command:engine.commandController.pendingCommands)if(command.team==unit.team&&command.getUnitIds().contains(unit.objectId))
                queued.add(command(command));
            row.put("pending",queued);
        }
        return row;
    }
    public static Map<String,Object> command(Command command) {
        UnitCommand w=command.unitCommand;
        return map("order",w==null?(command.stopCurrentAction?"stop":command.attackMode==null?"other":"mode"):w.getCommandType().name(),
                "target",w==null||w.getTargetUnit()==null?null:w.getTargetUnit().objectId,"x",w==null?null:w.getTargetX(),"y",w==null?null:w.getTargetY(),
                "queue",command.isQueued,"mode",command.attackMode==null?null:command.attackMode.name());
    }
}
