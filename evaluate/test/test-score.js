'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {scoreEvents,deduction,DEFAULTS,FAMILY,fundsEvents,impactWeight}=require('../events/score');
const {curve}=require('../scoring-curves');
const {economySeconds,economyCsv,timelineCsv,reportText,main}=require('../score_stage_2');
const event=(type,extra={})=>({id:type+'-1',type,t_start:0,t_confirm:5000,t_end:60000,status:'DETECTED',...extra});
const loss=(maxHp,hpLoss,building=false)=>({maxHp,hpLoss,building});
const ambush=extra=>event('AMBUSH',{impactComplete:true,hpLosses:[loss(3000,12000)],...extra});
const economy=(n,funds=5000)=>Array.from({length:n},(_,second)=>({second,funds,incomePerSecond:10}));

test('natural functions and quadratics use original x coordinates and interpolate endpoints',()=>{
  assert.equal(curve(8,0,8,0,.3,'exp'),.3);
  assert.ok(Math.abs(curve(4,0,8,0,.3,'exp')-.3*(Math.exp(4)-1)/(Math.exp(8)-1))<1e-12);
  assert.ok(Math.abs(curve(40,25,60,.75,1,'log')-(.75+.25*Math.log(40/25)/Math.log(60/25)))<1e-12);
  // y=-2x²+15.25x+6, clipped to the deduction cap. The specified a causes an interior maximum.
  assert.equal(curve(0,0,8,6,0,'quadratic',-2),6);
  assert.equal(curve(4,0,8,6,0,'quadratic',-2),6);
  assert.equal(curve(8,0,8,6,0,'quadratic',-2),0);
  assert.ok(Math.abs(curve(.15,0,.3,10,0,'quadratic',3)-4.9325)<1e-10);
  assert.ok(Number.isFinite(curve(10000,0,20000,0,1,'exp')));
});
test('legacy names retain families; combat families still need HP evidence',()=>{
  assert.equal(FAMILY.FEED,'GRINDER');assert.equal(FAMILY.PRODUCTION_GAP,'FACTORY_IDLE');
  assert.equal(deduction(event('FEED',{losses:{tank:99}})).reason,'missing_hp_loss_evidence');
  // A legacy idle name keeps its family and now scores from duration alone, like the D/E pair it was
  // replaced by: the evidence gap is no longer an excuse.
  assert.equal(deduction(event('BUILDER_GAP',{gapMs:5000})).reason,'idle_with_endpoint_work_available');
});
test('HP impact uses each maximum HP and fixed category weights, excluding shield loss',()=>{
  assert.equal(impactWeight(loss(500,1),'AMBUSH'),1);
  assert.equal(impactWeight(loss(501,1),'AMBUSH'),2);
  assert.equal(impactWeight(loss(1501,1),'AMBUSH'),3);
  assert.equal(impactWeight(loss(2001,1,true),'AMBUSH'),4);
  const row=deduction(ambush({hpLosses:[loss(3000,1500),loss(300,300),loss(3000,750,true)]}));
  assert.equal(row.impact,3.5);
  assert.equal(deduction(ambush({hpLosses:[loss(300,0)]})).points,0);
});
test('ambush dead zone, convex severity, effective response and category cap',()=>{
  assert.equal(deduction(ambush({hpLosses:[loss(300,300)]})).points,0);
  assert.equal(deduction(ambush()).points,18);
  assert.equal(deduction(ambush({effectiveResponseMs:20000})).points,9);
  assert.equal(deduction(ambush({effectiveResponseMs:1000})).points,0);
  assert.equal(deduction(ambush({scoreable:false})).points,0);
  assert.equal(deduction(ambush({status:'UNCERTAIN'})).points,0);
  const score=scoreEvents([ambush({id:'a'}),ambush({id:'b'}),ambush({id:'c'})]);
  assert.equal(score.breakdown[0].raw,54);assert.equal(score.deduction,40);assert.equal(score.total,60);
});
test('grinder reverses unit weights and uses its own dead zone and response time',()=>{
  const row=extra=>event('GRINDER',{impactComplete:true,hpLosses:[loss(300,1800)],...extra});
  assert.equal(deduction(row()).points,15);
  assert.equal(deduction(row({effectiveResponseMs:27500})).points,7.5);
  assert.equal(deduction(row({hpLosses:[loss(300,300)]})).points,0);
  assert.equal(impactWeight(loss(3000,1),'GRINDER'),1);
  assert.equal(scoreEvents([row(),row()]).deduction,20);
});
test('stale commands charge each known age once with strict age bands and type weights',()=>{
  const seconds=[5,5.001,10,20,40,60];
  const rows=seconds.map((seconds,i)=>({commandId:String(i),staleInfo:true,staleAgeMs:seconds*1000,kind:'operation'}));
  const result=deduction(event('STALE_BURST',{commands:rows}));
  assert.equal(result.points,16);
  assert.deepEqual(result.items.map(row=>row.factor),[0,.5,1,1.5,2,3]);
  assert.equal(scoreEvents([event('STALE_BURST',{commands:rows})]).deduction,15);
  assert.equal(deduction(event('STALE_BURST',{commands:[{staleInfo:true,staleAgeMs:60000,kind:'configuration'}]})).points,3);
  assert.equal(deduction(event('STALE_BURST',{commands:[{staleInfo:true}]})).missing,1);
});
test('idle curves have separate caps and score unproven endpoint work',()=>{
  const idle=(type,seconds,extra={})=>event(type,{durationMs:seconds*1000,feasibility:{throughout:true},...extra});
  assert.equal(deduction(idle('BUILDER_IDLE',8)).points,2.4);
  assert.equal(deduction(idle('BUILDER_IDLE',25)).points,6);
  assert.equal(deduction(idle('BUILDER_IDLE',60)).points,8);
  assert.equal(deduction(idle('FACTORY_IDLE',10)).points,0);
  assert.equal(deduction(idle('FACTORY_IDLE',30)).points,1.5);
  assert.equal(deduction(idle('FACTORY_IDLE',120)).points,5);
  assert.equal(deduction(idle('FACTORY_IDLE',900)).points,5);
  // Unknown feasibility costs exactly what proven feasibility costs: an idle pair is an engine-side
  // fact, so only a refuted or excluded pair is free.
  assert.equal(deduction(idle('FACTORY_IDLE',900,{feasibility:null})).points,5);
  assert.equal(deduction(idle('FACTORY_IDLE',900,{status:'UNCERTAIN'})).points,5);
  assert.equal(deduction(idle('FACTORY_IDLE',900,{status:'EXPLAINED'})).points,0);
  assert.equal(deduction(idle('FACTORY_IDLE',900,{status:'EXCLUDED'})).points,0);
  assert.equal(scoreEvents(Array.from({length:8},(_,i)=>idle('FACTORY_IDLE',900,{id:String(i)}))).deduction,20);
});
test('funds intervals use elapsed time, median smoothing and the 15/120 second curve',()=>{
  assert.equal(fundsEvents(economy(121))[0].durationSeconds,120);
  const score=scoreEvents([],{economy:economy(121)});
  assert.equal(score.deduction,7);assert.equal(score.breakdown[0].family,'FUNDS');
  assert.equal(scoreEvents([],{economy:economy(16)}).deduction,0);
  const spike=economy(21,0);spike[10].funds=10000;
  assert.equal(fundsEvents(spike).length,0);
});
test('partial spending reduces current D while preserving its historical maximum',()=>{
  const rows=economy(85);for(let i=60;i<rows.length;i++)rows[i].funds=3000;
  for(let i=63;i<rows.length;i++)rows[i].funds=3100;
  const [interval]=fundsEvents(rows);
  assert.ok(interval.durationSeconds>=60&&interval.durationSeconds<70);
  assert.equal(interval.spends.length,1);assert.equal(interval.spends[0].share,.4);
});
test('full releases and falling below threshold split funds intervals',()=>{
  const rows=economy(151);for(let i=61;i<=64;i++)rows[i].funds=500;
  const intervals=fundsEvents(rows);assert.equal(intervals.length,2);
  assert.ok(intervals[0].spends[0].share>.8);
  assert.ok(intervals[0].durationSeconds>0&&intervals[1].durationSeconds>0);
});
test('caps, config and unknown or missing evidence remain auditable',()=>{
  const score=scoreEvents([ambush(),event('UNKNOWN')],{weights:{AMBUSH_SINGLE_MAX:7},caps:{AMBUSH:5}});
  assert.equal(score.breakdown[0].raw,7);assert.equal(score.deduction,5);
  assert.equal(DEFAULTS.weights.AMBUSH_SINGLE_MAX,18);assert.equal(DEFAULTS.caps.AMBUSH,40);
  assert.equal(score.excluded.UNKNOWN,1);assert.equal(score.excluded.FUNDS_EVIDENCE_MISSING,1);
  assert.throws(()=>scoreEvents([],{ambush:{fullImpact:1}}));
});
test('timeline uses the same overridden score and contains financial intervals',()=>{
  const rows=[ambush({t_start:100,id:'a'})];
  const score=scoreEvents(rows,{weights:{AMBUSH_SINGLE_MAX:9},economy:economy(121)});
  const csv=timelineCsv(rows,score);
  assert.match(csv,/100,60000,AMBUSH,AMBUSH,DETECTED,yes,9,weighted_hp_loss_and_response/);
  assert.match(csv,/FUNDS,FUNDS,DETECTED,yes,7,funds_hoarding/);
  assert.match(reportText(score),/stage2-v2/);assert.match(reportText(score),/F（资金空余）参与计分/);
});
test('CLI reconstructs economy, applies overrides and writes consistent report artifacts',()=>{
  const root=path.resolve(__dirname,'../../agent-tmp');fs.mkdirSync(root,{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'score-v2-'));
  try{
    fs.writeFileSync(path.join(dir,'events.jsonl'),JSON.stringify(ambush())+'\n');
    fs.writeFileSync(path.join(dir,'weights.json'),JSON.stringify({weights:{AMBUSH_SINGLE_MAX:9}}));
    const values=economy(121).map(()=> '5000(+6.4)'),lines=[];
    for(let i=0;i<values.length;i+=60)lines.push(values.slice(i,i+60).join(','));
    fs.writeFileSync(path.join(dir,'economy.log'),lines.join('\n')+'\n');
    const rows=economySeconds(path.join(dir,'economy.log'));
    assert.equal(rows.length,121);assert.equal(rows[1].fundsDelta,0);
    assert.match(economyCsv(rows),/^second,funds,incomeField,fundsDelta/);
    const {score}=main(['node','score_stage_2',dir,'--weights',path.join(dir,'weights.json')]);
    assert.equal(score.deduction,16);
    assert.match(fs.readFileSync(path.join(dir,'timeline.csv'),'utf8'),/,yes,9,weighted_hp_loss_and_response/);
    assert.ok(fs.existsSync(path.join(dir,'score.txt')));
    fs.writeFileSync(path.join(dir,'economy.log'),'malformed');assert.throws(()=>economySeconds(path.join(dir,'economy.log')));
  }finally{assert.ok(dir.startsWith(root+path.sep));fs.rmSync(dir,{recursive:true,force:true});}
});
