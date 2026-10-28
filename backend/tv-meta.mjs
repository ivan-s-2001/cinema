import {titleDetail, episode} from './catalog-model.mjs';

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
  const ratings=title.ratings.map(r=>({source:r.source.toLowerCase(),label:ratingLabels[r.source]||r.source,value:r.value,scale:r.scale}));
  if(ratings.length)meta.cinemaRatings=ratings;
  const imdb=title.ratings.find(r=>r.source==='imdb'&&r.scale===10);
  if(imdb)meta.imdbRating=imdb.value.toFixed(1);
  if(title.type==='series')meta.videos=episodeRows.filter(r=>r.season_number>0).map(row=>{
    const ep=episode(row),video={id:ep.id,season:ep.season,episode:ep.number,title:ep.title||'Серия '+ep.number};
    if(ep.still)video.thumbnail=ep.still;
    if(ep.airAt)video.released=instant(ep.airAt);
    return video;
  });
  return {title,meta};
}
