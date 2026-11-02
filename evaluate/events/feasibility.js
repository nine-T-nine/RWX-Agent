'use strict';
/** B is the actual endpoint work, never a previous product or a cheap substitute. */
function endpointFeasibility(row,factory){
  const proof=row.feasibility;
  const fail=(reason,throughout=null)=>({throughout,reason});
  if(!proof?.complete||proof.fromMs!==row.fromMs||proof.toMs!==row.toMs)return fail('continuous_feasibility_unavailable');
  if(factory){
    const queue=row.closeQueue;
    if(row.closeReason!=='PRODUCE'||!queue?.length||queue.some(q=>q.kind!=='produce'))return fail('endpoint_production_unavailable');
    let cost=0,count=0;
    for(const item of queue){
      const amount=item.count??1,price=proof.actions?.[item.action];
      if(!Number.isFinite(amount)||amount<=0)return fail('endpoint_batch_unknown');
      if(!Number.isFinite(price))return fail('endpoint_action_not_available_throughout',false);
      cost+=price*amount;count+=amount;
    }
    if(!Number.isFinite(proof.minFunds)||!Number.isFinite(proof.minCap))return fail('continuous_budget_unavailable');
    if(proof.minFunds<cost||proof.minCap<count)return fail('endpoint_batch_not_affordable_throughout',false);
    return {throughout:true,reason:'endpoint_production_feasible_throughout',cost,count,queue};
  }
  const task=row.closeTask;
  if(task?.type==='repair')return (proof.repairable||[]).includes(task.targetId)
    ?{throughout:true,reason:'endpoint_repair_feasible_throughout',task}:fail('endpoint_repair_not_valid_throughout',false);
  if(task?.type!=='build')return fail('endpoint_build_or_repair_unavailable');
  const action=Object.entries(row.actions||{}).find(([,a])=>a.kind==='build'&&a.type===task.buildType);
  if(!action)return fail('endpoint_build_action_unknown');
  const price=proof.actions?.[action[0]],count=task.count||1;
  if(!Number.isFinite(price))return fail('endpoint_action_not_available_throughout',false);
  if(!Number.isFinite(proof.minFunds))return fail('continuous_budget_unavailable');
  if(proof.minFunds<price*count)return fail('endpoint_build_not_affordable_throughout',false);
  // Placement at B cannot prove that the same site was free at A or in the middle.
  const site=proof.placements?.find(p=>p.type===task.buildType&&p.x===task.x&&p.y===task.y
    &&p.count===count&&p.fromMs===row.fromMs&&p.toMs===row.toMs);
  if(!site)return fail('continuous_placement_unavailable');
  return site.valid===true?{throughout:true,reason:'endpoint_build_feasible_throughout',task,cost:price*count}
    :fail('endpoint_placement_not_valid_throughout',false);
}
module.exports={endpointFeasibility};
