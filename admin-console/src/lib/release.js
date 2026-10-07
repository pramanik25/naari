import release from "./release.json";

export const APK_FILE_NAME = "naari-shakti.apk";

// Where /download sends people when no APK is staged on this server and APK_URL is unset:
// the asset of that name on the newest GitHub release.
export const DEFAULT_APK_URL = `https://github.com/pramanik25/NaariShakti2/releases/latest/download/${APK_FILE_NAME}`;

export const MIN_ANDROID = "7.0";

export function releaseInfo() {
  return {
    ...release,
    sizeMb: Math.round(release.sizeBytes / (1024 * 1024)),
  };
}
