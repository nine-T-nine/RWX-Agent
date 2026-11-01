'use strict';
const path=require('node:path');
const OUTPUT=path.resolve(__dirname,'../output');
function matchDirectory(commands,root=OUTPUT) {
  const name=path.parse(commands).name.replace(/^commands?-?/i,'');
  if(!name)throw Error('Command log filename must contain a match name');
  return path.resolve(root,name);
}
function stageDirectory(commands,stage,root=OUTPUT){return path.join(matchDirectory(commands,root),'stage_'+stage);}
module.exports={OUTPUT,matchDirectory,stageDirectory};
