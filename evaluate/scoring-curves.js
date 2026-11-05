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
  if(value<=lo)return y0;
  if(value>=hi)return y1;
  if(kind==='exp'){
    // Solve the two endpoint equations for the translated form y=e^(x-a)+b.
    // This is algebraically identical to the old normalized exponential, but keeps the
    // published equation in the requested translated form.
    const d=y1-y0,q=Math.exp(hi-lo),ea=d/(q-1),shift=lo-Math.log(ea),offset=y0-ea;
    return Math.exp(value-shift)+offset;
  }
  if(kind==='log'){
    // Solve y=ln(x-a)+b through both endpoints. The segment must stay in the domain x>a.
    const d=y1-y0;
    if(!(d>0)||!(lo>0&&hi>lo))return y0+(y1-y0)*u;
    const ratio=Math.exp(d),shift=(ratio*lo-hi)/(ratio-1),offset=y0-Math.log(lo-shift);
    return Math.log(value-shift)+offset;
  }
  return y0+(y1-y0)*u;
}
function piecewise(x,segments,missing=0){
  if(!Number.isFinite(x))return missing;
  for(const [lo,hi,y0,y1,kind,a] of segments)if(x<=hi)return curve(x,lo,hi,y0,y1,kind,a);
  return segments.at(-1)[3];
}
module.exports={clamp,curve,piecewise};
