import {createHash} from 'node:crypto';
const escape=value=>value.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[c]));
export const titleArtUrl=id=>'https://cinemahome.duckdns.org/api/v2/titles/'+id+'/logo.svg';
// A transparent typographic wordmark, used only when the provider has no logo.
// It is not presented as original studio artwork. No fonts, scripts or URLs are embedded.
export function titleArt(title){
 const label=String(title).replace(/[\u0000-\u001f\u007f]/g,' ').trim().slice(0,512);
 const words=label.split(/\s+/),lines=[];
 for(const word of words){if(!lines.length||[...lines.at(-1)+' '+word].length>25)lines.push(word);else lines[lines.length-1]+=' '+word;}
 if(!lines.length)lines.push('CinemaHall');
 const longest=Math.max(...lines.map(s=>[...s].length)),font=Math.min(116,Math.max(12,Math.floor(1180/(longest*.68))));
 const step=Math.ceil(font*1.18),height=step*lines.length+48;
 const body=`<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="${height}" viewBox="0 0 1280 ${height}" role="img"><title>${escape(label)}</title><defs><linearGradient id="ink" x2="0" y2="1"><stop stop-color="#fff"/><stop offset="1" stop-color="#d9dfe8"/></linearGradient></defs><g font-family="sans-serif" font-weight="900" font-size="${font}" letter-spacing="0.5" fill="url(#ink)">${lines.map((line,i)=>`<text x="12" y="${24+font+i*step}">${escape(line)}</text>`).join('')}</g></svg>`;
 return {body,etag:'"'+createHash('sha256').update(body).digest('hex').slice(0,32)+'"'};
}

export function providerLogo(images={}){
 const path=value=>typeof value==='string'?value:typeof value==='object'&&value?(value.url||value.file_path||value.path||null):null;
 const primary=path(images.logo);if(primary)return primary;
 const logos=Array.isArray(images.logos)?images.logos:[];
 const rank=value=>({ru:0,en:1}[value?.iso_639_1||value?.language]??2);
 return logos.map((value,index)=>({value,index})).sort((a,b)=>rank(a.value)-rank(b.value)||a.index-b.index).map(entry=>path(entry.value)).find(Boolean)||null;
}
