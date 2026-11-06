#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const file = path.resolve(__dirname, '..', 'resource', 'scoring.json');
const data = JSON.parse(fs.readFileSync(file, 'utf8'));
const stage2 = data.fullMatch.stages.find(stage => stage.id === 'stage2');
const legacyNote = ' 它不参与 total-v2，只在材料允许时作为历史对照。';
stage2.label = '第二阶段（old_v1.1 可选）';
stage2.summary = (stage2.summary || '').replace(legacyNote, '') + legacyNote;
data.fullMatch.stages = [
  {id:'stage1',label:'第一阶段 v2',version:'stage1-score-v2',title:'交互节奏与持续操作',
    summary:'B 簇、观察—行动延迟与时间加权空窗评分；当前阈值、lambda 和活跃度闸门仍为 provisional。',
    items:[
      {name:'启动',cap:20,formula:'f1=首个 B；lo/hi=3/90s，经 log 归一与 g_lambda。',description:'衡量开局后多久发出第一条行动。'},
      {name:'回合延迟',cap:40,formula:'f2=Γ_AB 中位数，f3=Γ_BA 中位数；组内等分。',description:'同时约束观察后行动与行动后重新观察。'},
      {name:'空窗',cap:40,formula:'f5=B 簇空窗、f6=底层命令空窗，均取时间加权 P90。',description:'惩罚大批下令后长时间停顿；包含末尾空窗。'}]},
  stage2,
  {id:'stage3',label:'第三阶段',version:'stage3-score-v2',title:'严重战场失误与资金利用',
    summary:'偷袭、建筑炮击、危险区建造者死亡和资金空余四项独立累计后封顶。',items:[
      {name:'偷袭延迟',cap:50,formula:'P=50×(1−exp(−S/S0))；S0=17.678，时长按 20min 归一。',description:'空闲移动单位受击后迟迟没有行动，按损失、警告次数、时长与造价计分。'},
      {name:'偷家延迟',cap:50,formula:'P=50×(1−exp(−S/S0))；S0=12.122。',description:'建筑持续遭指定炮兵攻击，按实际 HP/护盾损失、命中次数和造价计分。'},
      {name:'危险区建造者死亡',cap:20,formula:'每事件 4×L×B，全局封顶 20。',description:'危险区已存在并过宽限期后，建造类单位仍在其中被击杀。'},
      {name:'资金空余 v2',cap:30,formula:'每区间 7×f(d)，K=30，d0/d1=15/120s，指数 lambda=1。',description:'资金超过近期收入阈值后长期未充分释放；动态标定未定部分保持 provisional。'}]}
];
data.updatedAt = new Date().toISOString();
fs.writeFileSync(file, JSON.stringify(data, null, 2) + '\n');
console.log(`Updated ${file}`);
