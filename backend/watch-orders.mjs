// Release order is explicit and versioned. Movie and TV IDs have separate namespaces.
export const watchOrders=[
 {id:'skywalker',name:'Звёздные войны: сага Скайуокеров',order:'Порядок выхода',source:'https://www.starwars.com/films',tmdb:[11,1891,1892,1893,1894,1895,140607,181808,181812]},
 {id:'wizarding-world',name:'Волшебный мир: Фантастические твари и Гарри Поттер',order:'Порядок выхода',source:'https://editorial.rottentomatoes.com/guide/harry-potter-movies-in-order/',tmdb:[671,672,673,674,675,767,12444,12445,259316,338952,338953]},
 {id:'middle-earth',name:'Средиземье: Хоббит и Властелин колец',order:'Порядок выхода',source:'https://www.warnerbros.com/movies/the-hobbit-an-unexpected-journey',tmdb:[120,121,122,49051,57158,122917]},
 {id:'matrix',name:'Матрица',order:'Порядок выхода',source:'https://www.warnerbros.com/movies/the-matrix',tmdb:[603,604,605,624860]},
 {id:'back-to-the-future',name:'Назад в будущее',order:'Порядок выхода',source:'https://www.backtothefuture.com/movies',tmdb:[105,165,196]},
 {id:'pirates',name:'Пираты Карибского моря',order:'Порядок выхода',source:'https://movies.disney.com/pirates-of-the-caribbean',tmdb:[22,58,285,1865,166426]},
 {id:'dark-knight',name:'Трилогия Тёмного рыцаря',order:'Порядок выхода',source:'https://www.warnerbros.com/movies/the-dark-knight',tmdb:[272,155,49026]},
 {id:'john-wick',name:'Джон Уик',order:'Порядок выхода',source:'https://www.johnwick.movie/',tmdb:[245891,324552,458156,603692]},
 {id:'infinity-saga',name:'Marvel: Сага Бесконечности',order:'Порядок выхода',source:'https://www.marvel.com/movies',tmdb:[1726,1724,10138,10195,1771,24428,68721,76338,100402,118340,99861,102899,271110,284052,283995,315635,284053,284054,299536,363088,299534,429617]},
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
export const orderParts=order=>order.parts||order.tmdb.map(id=>({id,type:'movie'}));
export function watchOrdersFor(tmdb,kind='movie'){return watchOrders.filter(order=>orderParts(order).some(p=>p.id===Number(tmdb)&&p.type===kind));}
export function orderedWatchItems(order,rows,preview){
 const byId=new Map(rows.map(row=>{const p=typeof row.payload==='string'?JSON.parse(row.payload):row.payload;return [(row.kind||'movie')+':'+Number(p?.ids?.tmdb||p?.metadataTmdbId),row];}));
 return orderParts(order).flatMap((part,index)=>{
  const row=byId.get(part.type+':'+part.id);
  return row?[{...preview(row),name:(index+1)+'. '+row.title,watchOrderPosition:index+1}]:[];
 });
}
