'use strict';
// Curves preserve the shape in original x coordinates and interpolate both endpoints.
const clamp=x=>Math.min(1,Math.max(0,x));
function curve(x,lo,hi,y0,y1,kind='linear',a=0){
  const u=clamp((x-lo)/(hi-lo));
  if(kind==='quadratic'){
    const b=(y1-y0-a*(hi*hi-lo*lo))/(hi-lo),c=y0-a*lo*lo-b*lo;
    const value=lo+(hi-lo)*u;
    return Math.min(Math.max(y0,y1),Math.max(Math.min(y0,y1),a*value*value+b*value+c));
  }
  const value=lo+(hi-lo)*u;
  const v=kind==='exp'?(Math.exp(value-hi)-Math.exp(lo-hi))/(-Math.expm1(lo-hi))
    :kind==='log'?Math.log(value/lo)/Math.log(hi/lo):u;
  return y0+(y1-y0)*v;
}
function piecewise(x,segments,missing=0){
  if(!Number.isFinite(x))return missing;
  for(const [lo,hi,y0,y1,kind,a] of segments)if(x<=hi)return curve(x,lo,hi,y0,y1,kind,a);
  return segments.at(-1)[3];
}
module.exports={clamp,curve,piecewise};
