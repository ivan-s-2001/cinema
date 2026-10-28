import test from 'node:test';
import assert from 'node:assert/strict';
import {titleArt,providerLogo} from '../title-art.mjs';
import {titleDetail} from '../catalog-model.mjs';
test('every title has image artwork; original provider logos are preferred and proxied at logo size',()=>{
 const missing=titleDetail({id:'ch:movie:1',kind:'movie',title:'A title',payload:{}});
 assert.equal(missing.logo,'https://cinemahome.duckdns.org/api/v2/titles/ch:movie:1/logo.svg');
 const original=titleDetail({id:'ch:movie:1',kind:'movie',title:'A title',payload:{images:{logo:'https://image.tmdb.org/t/p/original/logo.png'}}});
 assert.equal(original.logo,'https://cinemahome.duckdns.org/api/v2/images/tmdb/w500/logo.png');
});
test('generated title artwork is bounded, transparent, deterministic and escapes markup',()=>{
 const input='Фильм & кино </text><script>1</script>';
 const art=titleArt(input);
 assert.ok(art.body.startsWith('<svg'));assert.ok(art.body.includes('Фильм'));assert.ok(!art.body.includes('<script>'));
 assert.ok(art.body.includes('&lt;'));assert.ok(!art.body.includes('<image'));assert.ok(!art.body.includes('<rect'));
 assert.equal(titleArt(input).etag,art.etag);assert.notEqual(titleArt('Other').etag,art.etag);
 assert.ok(Buffer.byteLength(titleArt('Длинное название '.repeat(1000)).body)<12000);
});

test('logo alternatives prefer Russian artwork and keep a stable fallback language',()=>{
 assert.equal(providerLogo({logos:[{language:'en',url:'en.png'},{iso_639_1:'ru',file_path:'/ru.png'}]}),'/ru.png');
 assert.equal(providerLogo({logo:'primary.png',logos:['other.png']}),'primary.png');
});

test('short title images crop their transparent canvas and malformed provider descriptors are ignored',()=>{
 const art=titleArt('Дюна').body;const width=Number(/width="(\d+)"/.exec(art)[1]);assert.ok(width<800);
 assert.equal(providerLogo({logo:{url:42},logos:[{language:'ru',url:'correct.png'}]}),'correct.png');
});


test('Russian logo wins; English logo has a small Russian caption and beats unknown-language artwork',()=>{
 const row={id:'ch:movie:1',kind:'movie',title:'Русское название',payload:{titleRu:'Русское название',images:{logo:'https://image.tmdb.org/t/p/original/unknown.png',logos:[{language:'en',file_path:'/english.png'}]}}};
 let detail=titleDetail(row);assert.ok(detail.logo.endsWith('/english.png'));assert.equal(detail.logoLanguage,'en');assert.equal(detail.logoCaption,'Русское название');
 row.payload.images.logos.push({language:'ru',file_path:'/russian.png'});
 detail=titleDetail(row);assert.ok(detail.logo.endsWith('/russian.png'));assert.equal(detail.logoLanguage,'ru');assert.equal(detail.logoCaption,null);
 row.payload.images={};detail=titleDetail(row);assert.equal(detail.logoLanguage,'ru');assert.equal(detail.logoCaption,null);
});
