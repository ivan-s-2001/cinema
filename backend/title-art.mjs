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
 const longest=Math.max(...lines.map(s=>[...s].length)),font=Math.min(116,Math.max(12,Math.floor(1180/(longest*1.05))));
 const step=Math.ceil(font*1.18),height=step*lines.length+48,width=Math.min(1280,Math.ceil(longest*font)+48);
 const body=`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img"><title>${escape(label)}</title><defs><linearGradient id="ink" x2="0" y2="1"><stop stop-color="#fff"/><stop offset="1" stop-color="#d9dfe8"/></linearGradient></defs><g font-family="sans-serif" font-weight="900" font-size="${font}" letter-spacing="0.5" fill="url(#ink)">${lines.map((line,i)=>`<text x="12" y="${24+font+i*step}">${escape(line)}</text>`).join('')}</g></svg>`;
 return {body,etag:'"'+createHash('sha256').update(body).digest('hex').slice(0,32)+'"'};
}

export function providerLogoAsset(images={}){
 const path=value=>{const candidate=typeof value==='string'?value:typeof value==='object'&&value?(value.url||value.file_path||value.path):null;return typeof candidate==='string'&&candidate.length<=2048?candidate:null;};
 const locale=value=>{const code=typeof value==='string'?value.toLowerCase().split(/[-_]/)[0]:null;return code==='ru'||code==='en'?code:code? 'other':null;};
 const candidates=[];
 const add=(raw,fallbackLanguage=null)=>{
   const url=path(raw);if(!url)return;
   const language=locale(raw&&typeof raw==='object'?(raw.iso_639_1||raw.language||fallbackLanguage):fallbackLanguage);
   if(language==='other')return;
   candidates.push({url,language});
 };
 add(images.logo,images.logoLanguage||images.logo_language);
 for(const raw of Array.isArray(images.logos)?images.logos:[])add(raw);
 return candidates.sort((a,b)=>({ru:0,en:1}[a.language]??2)-({ru:0,en:1}[b.language]??2))[0]||null;
}
export const providerLogo=images=>providerLogoAsset(images)?.url||null;
