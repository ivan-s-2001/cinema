// Editorial order is explicit and versioned. Do not infer story chronology from release dates.
export const watchOrders=[
 {id:'skywalker',name:'Звёздные войны: сага Скайуокеров',order:'Хронология событий',source:'https://www.starwars.com/news/skywalker-saga-marathon',tmdb:[1893,1894,1895,11,1891,1892,140607,181808,181812]},
 {id:'wizarding-world',name:'Волшебный мир: Фантастические твари и Гарри Поттер',order:'Хронология событий',source:'https://editorial.rottentomatoes.com/guide/harry-potter-movies-in-order/',tmdb:[259316,338952,338953,671,672,673,674,675,767,12444,12445]},
 {id:'middle-earth',name:'Средиземье: Хоббит и Властелин колец',order:'Хронология двух трилогий',source:'https://www.warnerbros.com/movies/the-hobbit-an-unexpected-journey',tmdb:[49051,57158,122917,120,121,122]},
 {id:'matrix',name:'Матрица',order:'Порядок частей',source:'https://www.warnerbros.com/movies/the-matrix',tmdb:[603,604,605,624860]},
 {id:'back-to-the-future',name:'Назад в будущее',order:'Порядок частей',source:'https://www.backtothefuture.com/movies',tmdb:[105,165,196]},
 {id:'pirates',name:'Пираты Карибского моря',order:'Порядок частей',source:'https://movies.disney.com/pirates-of-the-caribbean',tmdb:[22,58,285,1865,166426]},
 {id:'dark-knight',name:'Трилогия Тёмного рыцаря',order:'Порядок частей',source:'https://www.warnerbros.com/movies/the-dark-knight',tmdb:[272,155,49026]},
 {id:'john-wick',name:'Джон Уик',order:'Порядок основной истории',source:'https://www.johnwick.movie/',tmdb:[245891,324552,458156,603692]},
 {id:'infinity-saga',name:'Marvel: Сага Бесконечности',order:'Хронология событий',source:'https://www.marvel.com/articles/movies/mcu-timeline-order-disney-plus',tmdb:[1771,299537,1726,10138,1724,10195,24428,76338,68721,100402,118340,283995,99861,102899,271110,284054,315635,284052,284053,363088,299536,299534,429617]}
];
export function watchOrdersFor(tmdb){return watchOrders.filter(order=>order.tmdb.includes(Number(tmdb)));}
export function orderedWatchItems(order,rows,preview){
 const byId=new Map(rows.map(row=>[Number(typeof row.payload==='string'?JSON.parse(row.payload).ids?.tmdb:row.payload?.ids?.tmdb),row]));
 return order.tmdb.flatMap((tmdb,index)=>byId.has(tmdb)?[{...preview(byId.get(tmdb)),name:(index+1)+'. '+byId.get(tmdb).title,watchOrderPosition:index+1}]:[]);
}
