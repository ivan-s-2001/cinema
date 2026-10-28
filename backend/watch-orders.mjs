// Release order is explicit and versioned. Movie and TV IDs have separate namespaces.
const releaseRegistry=[
 {id:'skywalker',name:'Звёздные войны: сага Скайуокеров',order:'Порядок выхода',source:'https://www.starwars.com/films',tmdb:[11,1891,1892,1893,1894,1895,140607,181808,181812]},
 {id:'wizarding-world',name:'Волшебный мир: Фантастические твари и Гарри Поттер',order:'Порядок выхода',source:'https://editorial.rottentomatoes.com/guide/harry-potter-movies-in-order/',tmdb:[671,672,673,674,675,767,12444,12445,259316,338952,338953]},
 {id:'middle-earth',name:'Средиземье: Хоббит и Властелин колец',order:'Порядок выхода',source:'https://www.warnerbros.com/movies/the-hobbit-an-unexpected-journey',tmdb:[120,121,122,49051,57158,122917]},
 {id:'matrix',name:'Матрица',order:'Порядок выхода',source:'https://www.warnerbros.com/movies/the-matrix',tmdb:[603,604,605,624860]},
 {id:'back-to-the-future',name:'Назад в будущее',order:'Порядок выхода',source:'https://www.backtothefuture.com/movies',tmdb:[105,165,196]},
 {id:'pirates',name:'Пираты Карибского моря',order:'Порядок выхода',source:'https://movies.disney.com/pirates-of-the-caribbean',tmdb:[22,58,285,1865,166426]},
 {id:'dark-knight',name:'Трилогия Тёмного рыцаря',order:'Порядок выхода',source:'https://www.warnerbros.com/movies/the-dark-knight',tmdb:[272,155,49026]},
 {id:'john-wick',name:'Джон Уик',order:'Порядок выхода',source:'https://www.johnwick.movie/',tmdb:[245891,324552,458156,603692]},
 {id:'infinity-saga',name:'Marvel: Сага Бесконечности',order:'Порядок выхода',source:'https://www.marvel.com/movies',tmdb:[1726,1724,10138,10195,1771,24428,68721,76338,100402,118340,99861,102899,271110,284052,283995,315635,284053,284054,299536,363088,299537,299534,429617]},
 {id:'westeros',name:'Мир «Игры престолов»',order:'Порядок выхода сериалов',source:'https://www.hbo.com/house-of-the-dragon',parts:[{id:1399,type:'series'},{id:94997,type:'series'}]},
 ...[
 ['despicable-me','Гадкий я и Миньоны',[20352,93456,211672,324852,438148,519182,1315772]],
 ['shrek','Шрек и Кот в сапогах',[808,809,810,10192,417859,315162]],
 ['toy-story','История игрушек',[862,863,10193,301528]],
 ['ice-age','Ледниковый период',[425,950,8355,57800,278154,774825]],
 ['kung-fu-panda','Кунг-фу Панда',[9502,49444,140300,1011985]],
 ['dragons','Как приручить дракона',[10191,82702,166428]],
 ['madagascar','Мадагаскар',[953,10527,80321,270946]],
 ['monsters','Корпорация монстров',[585,62211]],
 ['finding-nemo','В поисках Немо',[12,127380]],
 ['incredibles','Суперсемейка',[9806,260513]],
 ['cars','Тачки',[920,49013,260514]],
 ['sing','Зверопой',[335797,438695]],
 ['pets','Тайная жизнь домашних животных',[328111,412117]],
 ['jurassic','Парк и Мир Юрского периода',[329,330,331,135397,351286,507086]],
 ['alien','Чужой',[348,679,8077,8078,70981,126889,945961]],
 ['terminator','Терминатор',[218,280,296,534,87101,290859]],
 ['indiana-jones','Индиана Джонс',[85,87,89,217,335977]],
 ['die-hard','Крепкий орешек',[562,1573,1572,1571,47964]],
 ['mission-impossible','Миссия невыполнима',[954,955,956,56292,177677,353081,575264,575265]],
 ['ocean','Друзья Оушена',[161,163,298,402900]],
 ['sherlock-holmes','Шерлок Холмс',[10528,58574]],
 ['dune','Дюна: фильмы Дени Вильнёва',[438631,693134]]
 ].map(([id,name,tmdb])=>({id,name,tmdb,order:'Порядок выхода',source:id==='despicable-me'?'https://www.illumination.com/':'https://www.themoviedb.org/'}))
];
// A part's narrative role is editorial data, never inferred from release date,
// genre, actors or a shared universe. Branches retain their own sequel links.
const movie=id=>({id,type:'movie'}),series=id=>({id,type:'series'});
const key=part=>part.type+':'+part.id;
const branch=(parts,roles,anchor)=>({parts,roles,anchor});
const roles={
 original:'Оригинал',sequel:'Сиквел',prequel:'Приквел',spin_off:'Спин-офф',
 alternate_sequel:'Другая ветка',reboot:'Перезапуск',interquel:'Между частями',universe:'Общая вселенная'
};
const topology={
 skywalker:{main:[11,1891,1892,140607,181808,181812].map(movie),branches:[branch([1893,1894,1895].map(movie),['prequel'],movie(11))]},
 'wizarding-world':{main:[671,672,673,674,675,767,12444,12445].map(movie),branches:[branch([259316,338952,338953].map(movie),['prequel','spin_off'],movie(671))]},
 'middle-earth':{main:[120,121,122].map(movie),branches:[branch([49051,57158,122917].map(movie),['prequel'],movie(120))]},
 westeros:{main:[series(1399)],branches:[branch([series(94997)],['prequel','spin_off'],series(1399))]},
 'despicable-me':{main:[20352,93456,324852,519182].map(movie),branches:[
   branch([211672,438148].map(movie),['prequel','spin_off'],movie(20352)),
   branch([movie(1315772)],['prequel','spin_off'],movie(211672))]},
 shrek:{main:[808,809,810,10192].map(movie),branches:[branch([417859,315162].map(movie),['spin_off'],movie(808))]},
 madagascar:{main:[953,10527,80321].map(movie),branches:[branch([movie(270946)],['spin_off'],movie(953))]},
 monsters:{main:[movie(585)],branches:[branch([movie(62211)],['prequel'],movie(585))]},
 'ice-age':{main:[425,950,8355,57800,278154].map(movie),branches:[branch([movie(774825)],['spin_off'],movie(425))]},
 ocean:{main:[161,163,298].map(movie),branches:[branch([movie(402900)],['spin_off'],movie(161))]},
 alien:{main:[348,679,8077,8078].map(movie),branches:[
   branch([70981,126889].map(movie),['prequel'],movie(348)),
   branch([movie(945961)],['interquel'],movie(348))]},
 terminator:{main:[218,280,296,534].map(movie),branches:[
   branch([movie(87101)],['reboot'],movie(218)),
   branch([movie(290859)],['alternate_sequel'],movie(280))]},
 'infinity-saga':{scope:'universe'}
};
const marvelCycles=[
 ['avengers','Мстители',[24428,99861,299536,299534]],
 ['iron-man','Железный человек',[1726,10138,68721]],
 ['thor','Тор',[10195,76338,284053,616037]],
 ['captain-america','Первый мститель',[1771,100402,271110,822119]],
 ['guardians','Стражи Галактики',[118340,283995,447365]],
 ['ant-man','Человек-муравей',[102899,363088,640146]],
 ['doctor-strange','Доктор Стрэндж',[284052,453395]],
 ['black-panther','Чёрная пантера',[284054,505642]],
 ['captain-marvel','Капитан Марвел',[299537,609681]],
 ['spider-man-mcu','Человек-паук: фильмы с Томом Холландом',[315635,429617,634649]]
].map(([id,name,tmdb])=>({id,name,tmdb,source:'https://www.marvel.com/movies',order:'Порядок выхода'}));

function narrativeOrder(order){
 const parts=order.parts||order.tmdb.map(movie),spec=topology[order.id]||{},scope=spec.scope||'narrative';
 const classification=new Map(),connections=[];
 if(scope==='universe')for(const part of parts)classification.set(key(part),['universe']);
 else{
   const main=spec.main||parts;
   main.forEach((part,index)=>{
     classification.set(key(part),[index?'sequel':'original']);
     if(index)connections.push({from:part,to:main[index-1],type:'sequel'});
   });
   for(const b of spec.branches||[]){
     b.parts.forEach((part,index)=>{
       classification.set(key(part),b.roles);
       if(index)connections.push({from:part,to:b.parts[index-1],type:'sequel'});
       else for(const type of b.roles)connections.push({from:part,to:b.anchor,type});
     });
   }
 }
 return {...order,scope,parts:parts.map(part=>({...part,roles:classification.get(key(part))||[]})),connections};
}
export const watchOrders=[...releaseRegistry,...marvelCycles].map(narrativeOrder);
export const orderParts=order=>order.parts;
export function watchOrdersFor(tmdb,kind='movie'){
 return watchOrders.filter(order=>order.parts.some(p=>p.id===Number(tmdb)&&p.type===kind))
   .sort((a,b)=>(a.scope==='universe')-(b.scope==='universe')||a.parts.length-b.parts.length||a.id.localeCompare(b.id));
}
function rowKey(row){const p=typeof row.payload==='string'?JSON.parse(row.payload):row.payload;return (row.kind||'movie')+':'+Number(p?.ids?.tmdb||p?.metadataTmdbId);}
export function orderedWatchItems(order,rows,preview){
 const byId=new Map(rows.map(row=>[rowKey(row),row]));
 return order.parts.flatMap((part,index)=>{
   const row=byId.get(key(part));if(!row)return [];
   const item=preview(row),label=roles[part.roles[0]]||'';
   return [{...item,name:(index+1)+'. '+row.title,releaseInfo:[item.releaseInfo,label].filter(Boolean).join(' · '),
     watchOrderPosition:index+1,cycleRoles:part.roles,cycleRoleLabel:label}];
 });
}
export function availableConnections(order,rows){
 const byId=new Map(rows.map(row=>[rowKey(row),row.id]));
 return order.connections.flatMap(link=>{
   const fromId=byId.get(key(link.from)),toId=byId.get(key(link.to));
   return fromId&&toId?[{fromTitleId:fromId,toTitleId:toId,type:link.type,source:order.source}]:[];
 });
}
