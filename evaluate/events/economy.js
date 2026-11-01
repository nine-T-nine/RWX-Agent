'use strict';
const fs=require('node:fs');
class EconomyTimeline {
  constructor(file){
    this.file=file;this.samples=[];
    const lines=fs.readFileSync(file,'utf8').replace(/^\uFEFF/,'').trim().split(/\r?\n/);
    if(!lines[0])throw Error('economy.log is empty');
    for(const [line,raw]of lines.entries()){
      const values=raw.split(',');
      if(values.length>60||line<lines.length-1&&values.length!==60)throw Error('economy.log must contain 60 samples per complete line');
      for(const value of values){
        const m=value.match(/^(-?\d+(?:\.\d+)?(?:e[+-]?\d+)?)\(([+-]?\d+(?:\.\d+)?(?:e[+-]?\d+)?)\)$/i);
        if(!m||!Number.isFinite(Number(m[1]))||!Number.isFinite(Number(m[2])))throw Error('Invalid economy.log sample: '+value);
        this.samples.push({sampleMs:this.samples.length*1000,funds:Number(m[1]),income:Number(m[2])});
      }
    }
  }
  at(timeMs){return this.samples[Math.floor(timeMs/1000)]??null;}
  compare(start,end){const a=this.at(start),b=this.at(end);return {start:a,end:b,
    fundsDelta:a&&b?b.funds-a.funds:null,incomeDelta:a&&b?b.income-a.income:null};}
  text(){const lines=[];for(let i=0;i<this.samples.length;i+=60)lines.push(this.samples.slice(i,i+60).map(s=>`${s.funds}(${s.income>=0?'+':''}${s.income})`).join(','));return lines.join('\n')+'\n';}
}
module.exports={EconomyTimeline};
