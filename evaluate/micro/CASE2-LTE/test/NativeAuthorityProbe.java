import com.corrodinggames.rts.game.ai.custom.micro.*;
import com.corrodinggames.rts.game.ai.custom.micro.case2.Case2Lte;
import com.corrodinggames.rts.game.ai.custom.rwx_cli_tool.RwxEngineLocalGameService;
import com.corrodinggames.rts.game.units.*;
import com.corrodinggames.rts.game.units.actions.ActionId;
import com.corrodinggames.rts.gameFramework.*;

/** Test-only launcher: attempts direct and queued native bypasses, then runs the normal desktop entry. */
public final class NativeAuthorityProbe {
    public static void main(String[] args)throws Exception {
        Thread probe=new Thread(()->{
            try {
                while(MicroRuntime.current()==null||!MicroRuntime.current().ready)Thread.sleep(20);
                RwxEngineLocalGameService.onGameThread(()->{
                    GameEngine e=GameEngine.getInstance();MicroRuntime run=MicroRuntime.current();
                    OrderableUnit b=run.units.get(1000L);
                    if(BaseUnit.bE.size()!=1||!b.r().v().equals("builder"))throw new AssertionError("Initial units");
                    if(com.corrodinggames.rts.game.PlayerTeam.getSortedTeams(true).stream().filter(t->t.teamId>=0).count()!=1)throw new AssertionError("Extra player teams");
                    double credits=b.team.credits;float x=b.posX,y=b.posY;
                    for(int kind=0;kind<8;kind++)for(boolean queued:new boolean[]{false,true}) {
                        Command c=queued?e.commandController.newCommandForTeam(b.team):e.commandController.createCommand();
                        c.team=b.team;c.addUnitToCommand(b);
                        switch(kind) {
                            case 0 -> c.setMoveTarget(10,10);
                            case 1 -> c.setBuildTarget(100,100,UnitTypeEnum.turret,1);
                            case 2 -> c.setBuildTarget(100,100,Case2Lte.type(1),1);
                            case 3 -> c.setReclaimTarget(b);
                            case 4 -> c.setClearExistingOrders();
                            case 5 -> c.setActionId(ActionId.intern("extractorT3_overclocked_0"));
                            case 6 -> c.setActionId(ActionId.intern("extractorT3_reinforced_1"));
                            case 7 -> c.setAttackMode(AttackMode.aggressive);
                        }
                        if(!queued)c.executeCommand();
                    }
                    if(b.posX!=x||b.posY!=y||b.team.credits!=credits||b.getCurrentWaypoint()!=null||BaseUnit.bE.size()!=1)
                        throw new AssertionError("Rejected commands changed state");
                    System.out.println("RWX_LTE_AUTHORITY_VERIFIED direct=8 queued=8 initialUnits=1");return null;
                });
            }catch(Throwable e){e.printStackTrace();System.exit(2);}
        },"lte-authority-probe");
        probe.setDaemon(true);probe.start();
        Class.forName("io.github.rwx.DesktopMain").getMethod("main",String[].class).invoke(null,(Object)args);
    }
}
