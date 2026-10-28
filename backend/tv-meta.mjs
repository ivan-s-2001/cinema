import {titleDetail, titleCard, episode, object} from './catalog-model.mjs';

const ratingLabels={kinopoisk:'Кинопоиск',imdb:'IMDb',tmdb:'TMDB',metacritic:'Metacritic',rottentomatoes:'Rotten Tomatoes',myAnimeList:'MyAnimeList',shikimori:'Shikimori',anilist:'AniList'};
const instant=value=>value ? (value.length===10?value+'T00:00:00.000Z':value) : undefined;

// The existing TV add-on expects one complete videos array. Keep its construction
// on the server, and reject oversized results rather than silently truncate them.
export function tvMetadata(row, episodeRows=[]) {
  const title=titleDetail(row);
  const meta={id:title.id,type:title.type,name:title.title,poster:title.poster||'',
    releaseInfo:title.year===null?'Год не указан':String(title.year),description:title.description||'',
    genres:title.genres,aliases:title.aliases};
  if(title.backdrop)meta.background=title.backdrop;
  if(title.runtimeMinutes)meta.runtime=String(title.runtimeMinutes);
  if(title.releaseDate)meta.released=instant(title.releaseDate);
  if(title.externalIds.imdb)meta.imdb_id=title.externalIds.imdb;
  if(title.ageRating!==null)meta.app_extras={certificationLocal:String(title.ageRating)};
  if(title.logo){meta.logo=title.logo;meta.logoLanguage=title.logoLanguage;meta.logoCaption=title.logoCaption;}
  if(title.status)meta.status=title.status;
  if(title.countries.length)meta.country=title.countries.join(', ');
  if(title.languages.length)meta.language=title.languages.join(', ');
  for(const [field,target]of [['cast','cast'],['directors','director'],['writers','writer']]){
    if(title[field].length){
      meta[target]=title[field].map(person=>person.name);
      meta.app_extras={...meta.app_extras,[field]:title[field].map(({id,name,character,photo})=>({personId:id?.startsWith('person:')?id:undefined,name,character,photo}))};
    }
  }
  const ratings=title.ratings.map(r=>({source:r.source.toLowerCase(),label:ratingLabels[r.source]||r.source,value:r.value,scale:r.scale}));
  if(ratings.length)meta.cinemaRatings=ratings;
  const imdb=title.ratings.find(r=>r.source==='imdb'&&r.scale===10);
  if(imdb)meta.imdbRating=imdb.value.toFixed(1);
  if(title.type==='series')meta.videos=episodeRows.filter(r=>r.season_number>0).map(row=>{
    const ep=episode(row),video={id:ep.id,season:ep.season,episode:ep.number,title:ep.title||'Серия '+ep.number};
    const seasonAir=object(row.payload).seasonAirAt;
    if(seasonAir&&seasonAir>new Date().toISOString().slice(0,10))video.available=false;
    if(ep.description)video.overview=ep.description;
    if(ep.still)video.thumbnail=ep.still;
    if(ep.airAt)video.released=instant(ep.airAt);
    return video;
  });
  return {title,meta};
}

export function tvPreview(row){
  const card=titleCard(row);
  return {id:card.id,type:card.type,name:card.title,logo:card.logo,logoLanguage:card.logoLanguage,logoCaption:card.logoCaption,poster:card.poster||'',background:card.backdrop||'',description:card.description||'',releaseInfo:card.year?String(card.year):'',genres:card.genres,cinemaRatings:card.ratings.map(r=>({...r,label:ratingLabels[r.source]||r.source}))};
}
