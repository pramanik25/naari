'use strict';

const EARTH_RADIUS_M = 6_371_008.8;
const M_PER_DEG_LAT = 111_320;
const rad = (d) => (d * Math.PI) / 180;

/** Great-circle distance in metres. */
function haversineM(lat1, lng1, lat2, lng2) {
  const dLat = rad(lat2 - lat1);
  const dLng = rad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

/**
 * Conservative lat/lng box around a point, used as an index-friendly prefilter before the exact
 * haversine check. Returns one or two lng ranges (two when the box crosses the antimeridian).
 */
function boundingBox(lat, lng, radiusM) {
  const pad = 1.01; // small safety margin; exact filtering happens afterwards
  const dLat = (radiusM / M_PER_DEG_LAT) * pad;
  const minLat = Math.max(-90, lat - dLat);
  const maxLat = Math.min(90, lat + dLat);
  const cos = Math.cos(rad(Math.max(Math.abs(minLat), Math.abs(maxLat))));
  if (cos < 1e-6 || maxLat >= 90 || minLat <= -90) {
    return { minLat, maxLat, lngRanges: [[-180, 180]] };
  }
  const dLng = Math.min(180, ((radiusM / (M_PER_DEG_LAT * cos)) * pad));
  const lo = lng - dLng;
  const hi = lng + dLng;
  let lngRanges;
  if (dLng >= 180) lngRanges = [[-180, 180]];
  else if (lo < -180) lngRanges = [[lo + 360, 180], [-180, hi]];
  else if (hi > 180) lngRanges = [[lo, 180], [-180, hi - 360]];
  else lngRanges = [[lo, hi]];
  return { minLat, maxLat, lngRanges };
}

module.exports = { haversineM, boundingBox };
