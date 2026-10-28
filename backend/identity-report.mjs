import {externalIds} from './catalog-identity.mjs';

// Public identifiers only: never serialize provider payloads or connection data.
export function identityReport(rows, registered=[], limit=50) {
  const titles=new Map(rows.map(row=>[row.id,row])), groups=new Map();
  function add(kind,provider,externalId,titleId,source){
    const key=JSON.stringify([kind,provider,externalId]);
    if(!groups.has(key))groups.set(key,{kind,provider,externalId,titles:new Map()});
    const group=groups.get(key);
    if(!group.titles.has(titleId)){
      const row=titles.get(titleId);
      group.titles.set(titleId,{id:titleId,title:row?.title||null,year:row?.year??null,sources:[]});
    }
    const item=group.titles.get(titleId);
    if(!item.sources.includes(source))item.sources.push(source);
  }
  for(const row of rows)for(const [provider,id]of externalIds(row.payload))add(row.kind,provider,id,row.id,'payload');
  for(const item of registered)add(item.kind,item.provider,item.external_id,item.title_id,'registered');
  const conflicts=[...groups.values()].filter(group=>group.titles.size>1);
  return {checkedTitles:rows.length,conflictCount:conflicts.length,truncated:conflicts.length>limit,
    conflicts:conflicts.slice(0,limit).map(group=>({...group,titles:[...group.titles.values()]}))};
}
