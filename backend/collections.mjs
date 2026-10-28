// Stable shelf IDs keep saved home layouts working. Rules run on the server.
const genre={thriller:['триллер','thriller'],comedy:['комедия','comedy'],scifi:['фантастика','science fiction'],animation:['мультфильм','animation'],drama:['драма','drama'],crime:['криминал','crime'],adventure:['приключения','adventure'],detective:['детектив','mystery'],horror:['ужасы','horror'],fantasy:['фэнтези','fantasy'],family:['семейный','family'],romance:['мелодрама','romance'],history:['история','history'],war:['военный','war'],documentary:['документальный','documentary'],music:['музыка','мюзикл','music']};
export const collections=[
 {id:'popular',name:'Популярные фильмы',type:'movie',sort:'popular',home:true,minRating:6},
 {id:'series',name:'Популярные сериалы',type:'series',sort:'popular',home:true,minRating:6},
 {id:'new',name:'Новые фильмы',type:'movie',sort:'year',home:true,recent:true,minRating:6},
 {id:'new_series',name:'Новые сериалы',type:'series',sort:'year',home:true,recent:true,minRating:6},
 {id:'thriller',name:'Триллеры',type:'movie',sort:'popular',home:true,genres:genre.thriller,minRating:6.5},
 {id:'comedy',name:'Комедии',type:'movie',sort:'popular',home:true,genres:genre.comedy,minRating:6.5},
 {id:'scifi',name:'Фантастика',type:'movie',sort:'popular',home:true,genres:genre.scifi,minRating:6},
 {id:'animation',name:'Мультфильмы',type:'movie',sort:'rating',home:true,genres:genre.animation,minRating:7},
 {id:'top',name:'Лучшие фильмы',type:'movie',sort:'rating',home:true,minRating:7.5},
 {id:'top_series',name:'Лучшие сериалы',type:'series',sort:'rating',home:true,minRating:7.5},
 ...Object.entries({thriller:'триллеры',comedy:'комедии',scifi:'фантастика',drama:'драмы',crime:'криминальные фильмы',adventure:'приключения',animation:'мультфильмы'}).flatMap(([id,label])=>[
  ...(['thriller','comedy','scifi','animation'].includes(id)?[]:[{id,name:label[0].toUpperCase()+label.slice(1),type:'movie',sort:'popular',home:false,genres:genre[id],minRating:6}]),
  {id:'new_'+id,name:(id==='scifi'?'Новая фантастика':'Новые '+label),type:'movie',sort:'year',home:false,genres:genre[id],recent:true,minRating:6}
 ]),
 ...Object.entries({detective:'Детективы',horror:'Ужасы',fantasy:'Фэнтези',family:'Семейное кино',romance:'Мелодрамы',history:'Исторические фильмы',war:'Военные фильмы',documentary:'Документальное кино',music:'Музыка и мюзиклы'}).map(([id,name])=>({id,name,type:'movie',sort:'popular',home:false,genres:genre[id],minRating:6})),
 {id:'short_evening',name:'До полутора часов',type:'movie',sort:'rating',home:false,runtimeMax:90,minRating:7},
 {id:'epics',name:'Большое кино: от трёх часов',type:'movie',sort:'rating',home:false,runtimeMin:180,minRating:7},
 {id:'classic',name:'Классика кино',type:'movie',sort:'rating',home:true,yearTo:1989,minRating:7.5},
 {id:'nineties',name:'Кино 90-х',type:'movie',sort:'rating',home:false,yearFrom:1990,yearTo:1999,minRating:7},
 {id:'two_thousands',name:'Кино 2000-х',type:'movie',sort:'rating',home:false,yearFrom:2000,yearTo:2009,minRating:7},
 {id:'space',name:'Космос и другие миры',type:'movie',sort:'popular',home:true,genres:genre.scifi,keywords:['космос','космич','планет','галакти','space','planet'],minRating:6.5},
 {id:'heists',name:'Ограбления и аферы',type:'movie',sort:'popular',home:true,genres:[...genre.crime,...genre.thriller],keywords:['ограб','афер','мошен','heist','robbery'],minRating:6.5},
 {id:'survival',name:'Борьба за выживание',type:'movie',sort:'popular',home:false,genres:[...genre.adventure,...genre.thriller,...genre.drama],keywords:['выжив','surviv'],minRating:6.5},
 {id:'detective_series',name:'Детективные сериалы',type:'series',sort:'rating',home:true,genres:[...genre.detective,...genre.crime],minRating:7},
 {id:'comedy_series',name:'Комедийные сериалы',type:'series',sort:'popular',home:false,genres:genre.comedy,minRating:7},
 {id:'fantasy_series',name:'Фантастика и фэнтези: сериалы',type:'series',sort:'popular',home:false,genres:[...genre.scifi,...genre.fantasy,'sci-fi & fantasy'],minRating:7}
];
export const rowSpec=({id,name,type})=>({id,name,type});
export const availableWhere='source_count>0 AND source_expires_at>UTC_TIMESTAMP(3)';
