// Home stays short. Genre shelves are available through the catalogue navigator.
export const collections=[
 {id:'popular',name:'Популярные фильмы',type:'movie',sort:'popular',home:true,minRating:6},
 {id:'series',name:'Популярные сериалы',type:'series',sort:'popular',home:true,minRating:6},
 {id:'new',name:'Новинки кино',type:'movie',sort:'year',home:true,recent:true,minRating:6},
 {id:'new_series',name:'Новые сериалы',type:'series',sort:'year',home:true,recent:true,minRating:6},
 {id:'thriller',name:'Популярные триллеры',type:'movie',sort:'popular',home:true,genres:['триллер','thriller'],minRating:6.5},
 {id:'comedy',name:'Комедии на вечер',type:'movie',sort:'popular',home:true,genres:['комедия','comedy'],minRating:6.5},
 {id:'scifi',name:'Популярная фантастика',type:'movie',sort:'popular',home:true,genres:['фантастика','science fiction'],minRating:6},
 {id:'animation',name:'Анимация с хорошими оценками',type:'movie',sort:'rating',home:true,genres:['мультфильм','animation'],minRating:7},
 {id:'top',name:'Высокие оценки: фильмы',type:'movie',sort:'rating',home:true,minRating:7.5},
 {id:'top_series',name:'Высокие оценки: сериалы',type:'series',sort:'rating',home:true,minRating:7.5},
 ...[
 ['thriller','триллеры',['триллер','thriller']],
 ['comedy','комедии',['комедия','comedy']],
 ['scifi','фантастика',['фантастика','science fiction']],
 ['drama','драмы',['драма','drama']],
 ['crime','криминальное кино',['криминал','crime']],
 ['adventure','приключения',['приключения','adventure']],
 ['animation','мультфильмы',['мультфильм','animation']]
 ].flatMap(([id,label,genres])=>[
  ...(['thriller','comedy','scifi','animation'].includes(id)?[]:[{id,name:'Популярные '+label,type:'movie',sort:'popular',home:false,genres,minRating:6}]),
  {id:'new_'+id,name:'Новинки жанра: '+label,type:'movie',sort:'year',home:false,genres,recent:true,minRating:6}
 ])
];
export const rowSpec=({id,name,type})=>({id,name,type});
export const availableWhere='source_count>0 AND source_expires_at>UTC_TIMESTAMP(3)';
