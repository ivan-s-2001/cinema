import {createHash,randomUUID} from 'node:crypto';
import {mkdir,readdir,readFile,stat,writeFile,rename,unlink} from 'node:fs/promises';
import {join} from 'node:path';

export function imageMime(body){
  if(body[0]===255&&body[1]===216&&body[2]===255)return 'image/jpeg';
  if(body.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))return 'image/png';
  if(body.subarray(0,4).toString()==='RIFF'&&body.subarray(8,12).toString()==='WEBP')return 'image/webp';
  return null;
}

/** Bounded persistent artwork, shared by staged and production API releases. */
export function createImageDiskCache({directory='/var/lib/cinemahall/image-cache/assets',budget=128*1024*1024,clock=Date.now}={}){
  const index=new Map();let total=0,serial=Promise.resolve();
  const fileFor=key=>createHash('sha256').update(key).digest('hex')+'.img';
  const ready=(async()=>{
    if(!directory)return;
    try{
      await mkdir(directory,{recursive:true,mode:0o700});
      for(const name of await readdir(directory)){
        if(!/^[a-f0-9]{64}\.img$/.test(name))continue;
        const info=await stat(join(directory,name));
        if(!info.isFile())continue;
        index.set(name,{size:info.size,time:info.mtimeMs});total+=info.size;
      }
    }catch(error){console.warn('Image disk cache initialization failed',error.code||error.name);}
  })();
  async function remove(name){
    const entry=index.get(name);if(entry){total-=entry.size;index.delete(name);}
    await unlink(join(directory,name)).catch(()=>{});
  }
  async function trim(){
    for(const [name] of [...index].sort((a,b)=>a[1].time-b[1].time)){
      if(total<=budget)break;
      await remove(name);
    }
  }
  return {
    async get(key){
      if(!directory)return null;await ready;
      const name=fileFor(key),file=join(directory,name);
      try{
        // Check before reading so corrupt or unrelated large files are never buffered.
        const info=await stat(file);
        if(info.size>2*1024*1024+256){await remove(name);return null;}
        const data=await readFile(file),line=data.indexOf(10);
        if(line<1||line>255)throw Error('Invalid cached image');
        const header=JSON.parse(data.subarray(0,line)),body=data.subarray(line+1);
        if(!['image/jpeg','image/png','image/webp'].includes(header.type)||!Number.isFinite(header.until)||header.until<=clock()||imageMime(body)!==header.type)throw Error('Expired or invalid cached image');
        return {status:200,body,type:header.type,until:header.until};
      }catch(error){if(error.code!=='ENOENT')await remove(name);return null;}
    },
    async put(key,item){
      if(!directory||!item.body||item.body.length>2*1024*1024||imageMime(item.body)!==item.type)return;
      serial=serial.catch(()=>{}).then(async()=>{
        await ready;
        const name=fileFor(key),file=join(directory,name),temporary=file+'.'+randomUUID()+'.tmp';
        const data=Buffer.concat([Buffer.from(JSON.stringify({type:item.type,until:clock()+7*86400000})+'\n'),item.body]);
        if(data.length>budget)return;
        try{
          await writeFile(temporary,data,{mode:0o600,flag:'wx'});await rename(temporary,file);
          if(index.has(name))total-=index.get(name).size;
          index.set(name,{size:data.length,time:clock()});total+=data.length;
          await trim();
        }finally{await unlink(temporary).catch(()=>{});}
      });
      try{await serial;}catch(error){console.warn('Image disk cache write failed',error.code||error.name);}
    }
  };
}
