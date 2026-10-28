import test from 'node:test';
import assert from 'node:assert/strict';
import {titleArt} from '../title-art.mjs';
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
